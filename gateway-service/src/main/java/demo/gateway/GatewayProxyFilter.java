package demo.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

final class PassthroughResponseErrorHandler implements ResponseErrorHandler {
  @Override
  public boolean hasError(ClientHttpResponse response) {
    return false;
  }

  @Override
  public void handleError(ClientHttpResponse response) {
    // Downstream 4xx/5xx responses are returned to the browser unchanged.
  }
}

@Component
class GatewayProxyFilter extends OncePerRequestFilter {
  private static final int MAX_REQUEST_BODY_BYTES = 1024 * 1024;
  private static final Logger log = LoggerFactory.getLogger(GatewayProxyFilter.class);
  private static final Set<String> HOP_BY_HOP_HEADERS =
      Set.of(
          "connection",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "host",
          "content-length");
  private static final Set<String> TRACE_PROPAGATION_HEADERS =
      Set.of(
          "traceparent",
          "tracestate",
          "x-datadog-trace-id",
          "x-datadog-parent-id",
          "x-datadog-sampling-priority",
          "x-datadog-origin",
          "x-datadog-tags");
  private static final Set<String> GATEWAY_MANAGED_RESPONSE_HEADERS = Set.of("ext_trace_id");

  private final RestTemplate restTemplate;
  private final String orderUrl;

  GatewayProxyFilter(
      RestTemplate gatewayRestTemplate,
      @Value("${gateway.order-url:http://127.0.0.1:8083}") String orderUrl) {
    this.restTemplate = gatewayRestTemplate;
    this.orderUrl = trimTrailingSlash(orderUrl);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getRequestURI().startsWith("/actuator");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getRequestURI().equals("/admin") || request.getRequestURI().startsWith("/admin/")) {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
      return;
    }
    String keyRequest = valueOrDash(request.getHeader("X-Key-Request"));
    String businessRequestId = valueOrDash(request.getHeader("X-Business-Request-Id"));
    DemoLanguage language = DemoLanguage.from(request.getHeader("X-Demo-Language"));
    putRequestContext(keyRequest, businessRequestId, language);
    applyCurrentSpanTags(keyRequest, businessRequestId, language);

    URI downstream = downstreamUri(request);
    long startedAt = System.nanoTime();
    log.info(
        language.text(
            "网关接入：方法={} 路径={} 下游={} 关键请求={} 业务请求ID={}",
            "Gateway request received: method={} path={} downstream={} key_request={} biz_request_id={}"),
        request.getMethod(),
        request.getRequestURI(),
        downstream,
        keyRequest,
        businessRequestId);
    try {
      HttpHeaders headers = requestHeaders(request);
      byte[] requestBody = request.getInputStream().readNBytes(MAX_REQUEST_BODY_BYTES + 1);
      if (requestBody.length > MAX_REQUEST_BODY_BYTES) {
        response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        return;
      }
      HttpEntity<byte[]> entity =
          new HttpEntity<>(requestBody.length == 0 ? null : requestBody, headers);
      ResponseEntity<byte[]> downstreamResponse =
          restTemplate.exchange(
              downstream, HttpMethod.valueOf(request.getMethod()), entity, byte[].class);
      writeResponse(response, downstreamResponse);
      long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
      log.info(
          language.text(
              "网关完成：方法={} 路径={} 状态={} 耗时={}ms 关键请求={} 业务请求ID={}",
              "Gateway request completed: method={} path={} status={} duration_ms={} key_request={} biz_request_id={}"),
          request.getMethod(),
          request.getRequestURI(),
          downstreamResponse.getStatusCode().value(),
          elapsedMs,
          keyRequest,
          businessRequestId);
    } catch (IllegalArgumentException | RestClientException exception) {
      long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
      log.error(
          language.text(
              "网关失败：方法={} 路径={} 耗时={}ms 关键请求={} 业务请求ID={} 原因={}",
              "Gateway request failed: method={} path={} duration_ms={} key_request={} biz_request_id={} reason={}"),
          request.getMethod(),
          request.getRequestURI(),
          elapsedMs,
          keyRequest,
          businessRequestId,
          exception.getMessage());
      response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "gateway downstream request failed");
    } finally {
      clearRequestContext();
    }
  }

  private URI downstreamUri(HttpServletRequest request) {
    String query = request.getQueryString();
    return URI.create(
        orderUrl + request.getRequestURI() + (query == null || query.isBlank() ? "" : "?" + query));
  }

  private HttpHeaders requestHeaders(HttpServletRequest request) {
    HttpHeaders headers = new HttpHeaders();
    Enumeration<String> names = request.getHeaderNames();
    while (names != null && names.hasMoreElements()) {
      String name = names.nextElement();
      String normalized = name.toLowerCase(Locale.ROOT);
      if (HOP_BY_HOP_HEADERS.contains(normalized)
          || TRACE_PROPAGATION_HEADERS.contains(normalized)) {
        continue;
      }
      Enumeration<String> values = request.getHeaders(name);
      while (values.hasMoreElements()) {
        headers.add(name, values.nextElement());
      }
    }
    headers.set("X-Forwarded-Host", valueOrDash(request.getHeader("Host")));
    headers.set("X-Forwarded-Proto", request.getScheme());
    headers.set("X-Forwarded-For", request.getRemoteAddr());
    headers.set("X-Gateway-Service", "gateway-service");
    return headers;
  }

  private void writeResponse(
      HttpServletResponse response, ResponseEntity<byte[]> downstreamResponse) throws IOException {
    response.setStatus(downstreamResponse.getStatusCode().value());
    downstreamResponse
        .getHeaders()
        .forEach(
            (name, values) -> {
              String normalized = name.toLowerCase(Locale.ROOT);
              if (HOP_BY_HOP_HEADERS.contains(normalized)
                  || GATEWAY_MANAGED_RESPONSE_HEADERS.contains(normalized)) {
                return;
              }
              for (String value : values) {
                response.addHeader(name, value);
              }
            });
    response.setHeader("X-Gateway-Service", "gateway-service");
    byte[] body = downstreamResponse.getBody();
    if (body != null && body.length > 0) {
      response.getOutputStream().write(body);
    }
  }

  private void applyCurrentSpanTags(
      String keyRequest, String businessRequestId, DemoLanguage language) {
    try {
      Class<?> globalTracer = Class.forName("datadog.trace.api.GlobalTracer");
      Object tracer = globalTracer.getMethod("get").invoke(null);
      Object span = tracer.getClass().getMethod("activeSpan").invoke(tracer);
      if (span != null) {
        setTag(span, "gateway.target", "order-service");
        setTag(span, "key_request", keyRequest);
        setTag(span, "biz_request_id", businessRequestId);
        setTag(span, "language", language.code());
      }
    } catch (ReflectiveOperationException | LinkageError ignored) {
      // Unit tests and local builds do not require the runtime tracing agent.
    }
  }

  private void setTag(Object span, String key, String value) throws ReflectiveOperationException {
    if (!"-".equals(value)) {
      span.getClass().getMethod("setTag", String.class, String.class).invoke(span, key, value);
    }
  }

  private void putRequestContext(
      String keyRequest, String businessRequestId, DemoLanguage language) {
    String processId = Long.toString(ProcessHandle.current().pid());
    String hostName = valueOrDash(System.getenv("HOSTNAME"));
    MDC.put("process_id", processId);
    MDC.put("host_process_id", processId);
    MDC.put("container_process_id", processId);
    MDC.put("host", valueOrDash(System.getenv("NODE_NAME")));
    MDC.put("host_name", hostName);
    MDC.put("pod_name", valueOrDash(System.getenv("POD_NAME")));
    MDC.put("pod_namespace", valueOrDash(System.getenv("POD_NAMESPACE")));
    MDC.put("container_name", valueOrDash(System.getenv("CONTAINER_NAME")));
    MDC.put("container_id", hostName);
    MDC.put("language", language.code());
    if (!"-".equals(keyRequest)) {
      MDC.put("key_request", keyRequest);
    }
    if (!"-".equals(businessRequestId)) {
      MDC.put("biz_request_id", businessRequestId);
    }
  }

  private void clearRequestContext() {
    for (String key :
        Set.of(
            "process_id",
            "host_process_id",
            "container_process_id",
            "host",
            "host_name",
            "pod_name",
            "pod_namespace",
            "container_name",
            "container_id",
            "key_request",
            "biz_request_id",
            "language")) {
      MDC.remove(key);
    }
  }

  private static String trimTrailingSlash(String value) {
    String result = value == null || value.isBlank() ? "http://127.0.0.1:8083" : value.trim();
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String valueOrDash(String value) {
    return value == null || value.isBlank() ? "-" : value.trim();
  }
}
