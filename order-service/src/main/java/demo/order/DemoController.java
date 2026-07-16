package demo.order;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/demo")
class DemoController {
  private static final Pattern BUSINESS_REQUEST_ID =
      Pattern.compile("^biz-[A-Za-z0-9._:-]{6,160}$");
  private static final Pattern ORDER_ID = Pattern.compile("^ord-[A-Za-z0-9._:-]{6,160}$");
  private static final List<FaultScenario> FAULT_SCENARIOS =
      List.of(
          new FaultScenario(
              "frontend_click_error",
              "前端点击无响应",
              "frontend",
              "javascript_error",
              "mall-h5",
              "browser",
              "client",
              "按钮点击触发未捕获 JS 异常，展示 RUM Error、Browser Log 和用户行为。",
              0,
              true),
          new FaultScenario(
              "frontend_slow_resource",
              "前端资源加载慢",
              "frontend",
              "slow_resource",
              "mall-h5",
              "browser",
              "client",
              "浏览器发起慢资源请求，展示 RUM Resource 慢加载。",
              0,
              true),
          new FaultScenario(
              "frontend_sourcemap_error",
              "SourceMap 源码定位错误",
              "frontend",
              "sourcemap_error",
              "mall-h5",
              "assets/checkout-sourcemap-fault.min.js",
              "client",
              "按钮点击触发压缩 JS 中的空指针错误，上传 SourceMap 后展示源码文件与原始行号。",
              0,
              true),
          new FaultScenario(
              "order_slow",
              "订单入口慢响应",
              "service",
              "latency",
              "order-service",
              "order-service",
              "order_slow",
              "订单入口 sleep，展示入口服务慢 Span 与接口响应慢。",
              300,
              false),
          new FaultScenario(
              "inventory_redis_timeout",
              "库存 Redis 超时",
              "dependency",
              "timeout",
              "inventory-service",
              "redis",
              "redis_timeout",
              "库存服务阻塞等待 Redis，展示依赖层故障和错误 Span。",
              300,
              false),
          new FaultScenario(
              "payment_slow",
              "支付慢方法",
              "service",
              "latency",
              "payment-service",
              "payment-service",
              "payment_slow",
              "支付服务 sleep，展示慢 Span、慢接口和 Profile。",
              300,
              false),
          new FaultScenario(
              "payment_error",
              "支付 5xx 错误",
              "service",
              "http_5xx",
              "payment-service",
              "payment-service",
              "payment_error",
              "支付服务返回 5xx，展示错误率、日志和失败 Span。",
              300,
              false),
          new FaultScenario(
              "payment_cpu_burn",
              "支付 CPU 繁忙",
              "jvm",
              "cpu",
              "payment-service",
              "payment-service",
              "payment_cpu_burn",
              "支付服务短时 CPU burn，展示 JVM/进程层资源异常。",
              180,
              false));

  private final RestTemplate restTemplate;
  private final String orderUrl;
  private final String inventoryUrl;
  private final String paymentUrl;
  private final String demoControlToken;
  private final String project;
  private final boolean rumEnabled;
  private final String rumApplicationId;
  private final String rumDatakitOrigin;
  private final String rumEnv;
  private final String rumVersion;
  private final String rumService;
  private final String observabilityConsoleUrl;
  private final String observabilityWorkspaceId;
  private final boolean kubernetesLogReaderEnabled;
  private final BackendLogReader backendLogReader;

  DemoController(
      RestTemplate restTemplate,
      @Value("${order.url:http://127.0.0.1:8080}") String orderUrl,
      @Value("${inventory.url:http://127.0.0.1:8081}") String inventoryUrl,
      @Value("${payment.url:http://127.0.0.1:8082}") String paymentUrl,
      @Value("${demo.control-token:}") String demoControlToken,
      @Value("${demo.project:mall-demo}") String project,
      @Value("${rum.enabled:false}") boolean rumEnabled,
      @Value("${rum.application-id:}") String rumApplicationId,
      @Value("${rum.datakit-origin:/rum-proxy}") String rumDatakitOrigin,
      @Value("${rum.env:${DD_ENV:demo}}") String rumEnv,
      @Value("${rum.version:${DD_VERSION:1.0.0}}") String rumVersion,
      @Value("${rum.service:mall-h5}") String rumService,
      @Value("${demo.observability-console-url:https://ap1-console.truewatch.com}")
          String observabilityConsoleUrl,
      @Value("${demo.observability-workspace-id:}") String observabilityWorkspaceId,
      @Value("${demo.log-directory:/var/log/observability-demo}") String logDirectory,
      @Value("${demo.kubernetes-log-reader.enabled:true}") boolean kubernetesLogReaderEnabled,
      @Value("${demo.kubernetes-log-reader.tail-lines:240}") int kubernetesLogTailLines,
      @Value("${demo.kubernetes-log-reader.since-seconds:600}") int kubernetesLogSinceSeconds) {
    this.restTemplate = restTemplate;
    this.orderUrl = orderUrl;
    this.inventoryUrl = inventoryUrl;
    this.paymentUrl = paymentUrl;
    this.demoControlToken = demoControlToken == null ? "" : demoControlToken;
    this.project = defaultIfBlank(project, "mall-demo");
    this.rumApplicationId = rumApplicationId == null ? "" : rumApplicationId.trim();
    this.rumEnabled = rumEnabled && !this.rumApplicationId.isBlank();
    this.rumDatakitOrigin = defaultIfBlank(rumDatakitOrigin, "/rum-proxy");
    this.rumEnv = defaultIfBlank(rumEnv, "demo");
    this.rumVersion = defaultIfBlank(rumVersion, "1.0.0");
    this.rumService = defaultIfBlank(rumService, "mall-h5");
    this.observabilityConsoleUrl =
        trimTrailingSlash(
            defaultIfBlank(observabilityConsoleUrl, "https://ap1-console.truewatch.com"));
    this.observabilityWorkspaceId =
        observabilityWorkspaceId == null ? "" : observabilityWorkspaceId.trim();
    this.kubernetesLogReaderEnabled = kubernetesLogReaderEnabled;
    this.backendLogReader =
        new CompositeBackendLogReader(
            new KubernetesBackendLogReader(
                kubernetesLogReaderEnabled, kubernetesLogTailLines, kubernetesLogSinceSeconds),
            new FileBackendLogReader(logDirectory));
  }

  @GetMapping("/config")
  Map<String, Object> config() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("rumEnabled", rumEnabled);
    response.put("logViewerEnabled", true);
    response.put("kubernetesLogReaderEnabled", kubernetesLogReaderEnabled);
    response.put("controlTokenRequired", true);
    response.put("project", project);
    if (!observabilityWorkspaceId.isBlank()) {
      response.put("observabilityConsoleUrl", observabilityConsoleUrl);
      response.put("workspaceId", observabilityWorkspaceId);
    }
    return response;
  }

  @GetMapping("/status")
  Map<String, Object> status() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("timestamp", Instant.now().toString());
    response.put("inventory", serviceStatus(inventoryUrl, true));
    response.put("payment", serviceStatus(paymentUrl, true));
    response.put("order", serviceStatus(orderUrl, true));
    return response;
  }

  @GetMapping("/rum-config")
  Map<String, Object> rumConfig() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("enabled", rumEnabled);
    response.put("applicationId", rumApplicationId);
    response.put("datakitOrigin", rumDatakitOrigin);
    response.put("service", rumService);
    response.put("env", rumEnv);
    response.put("version", rumVersion);
    response.put("project", project);
    response.put("traceType", "ddtrace");
    response.put("compressIntakeRequests", true);
    response.put("sessionSampleRate", 100);
    response.put("sessionReplaySampleRate", 100);
    response.put("sessionReplayOnErrorSampleRate", 100);
    response.put("keyRequestHeader", "X-Key-Request");
    response.put(
        "baggageKeys",
        java.util.List.of("project", "key_request", "biz_chain", "biz_request_id"));
    return response;
  }

  private String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  @GetMapping("/faults")
  Map<String, Object> faults() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("timestamp", Instant.now().toString());
    response.put("items", FAULT_SCENARIOS.stream().map(FaultScenario::toMap).toList());
    response.put("active", activeFaults());
    return response;
  }

  @PostMapping("/faults/{scenarioId}/enable")
  Map<String, Object> enableFaultScenario(
      @PathVariable("scenarioId") String scenarioId,
      @RequestHeader(name = "X-Demo-Control-Token", required = false) String controlToken) {
    requireControlToken(controlToken);
    FaultScenario scenario = faultScenario(scenarioId);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("scenario", scenario.toMap());
    response.put("timestamp", Instant.now().toString());
    if (scenario.clientSide()) {
      response.put("handledInBrowser", true);
      response.put("message", "client side scenario should be triggered by the browser");
      return response;
    }

    response.put("cleared", disableBackendFaults());
    String url =
        adminUrl(scenario.service())
            + "/admin/fault/"
            + scenario.mode()
            + "?ttlSeconds="
            + scenario.ttlSeconds();
    response.put("result", forwardPost(url));
    response.put("active", activeFaults());
    return response;
  }

  @PostMapping("/faults/off")
  Map<String, Object> disableAllFaults(
      @RequestHeader(name = "X-Demo-Control-Token", required = false) String controlToken) {
    requireControlToken(controlToken);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("timestamp", Instant.now().toString());
    response.put("results", disableBackendFaults());
    response.put("active", activeFaults());
    return response;
  }

  @GetMapping("/slow-resource")
  Map<String, Object> slowResource(
      @RequestParam(name = "delayMs", defaultValue = "2500") int delayMs)
      throws InterruptedException {
    int safeDelayMs = Math.max(200, Math.min(delayMs, 5000));
    Thread.sleep(safeDelayMs);
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("status", "SLOW_RESOURCE_READY");
    response.put("delayMs", safeDelayMs);
    response.put("timestamp", Instant.now().toString());
    return response;
  }

  @GetMapping("/logs")
  Map<String, Object> logs(
      @RequestParam(name = "biz_request_id", required = false) String businessRequestId,
      @RequestParam(name = "order_id", required = false) String orderId,
      @RequestParam(name = "limit", defaultValue = "8") int limit) {
    int safeLimit = Math.max(1, Math.min(limit, 30));
    List<String> needles = new ArrayList<>();
    addNeedle(needles, businessRequestId, BUSINESS_REQUEST_ID, "biz_request_id");
    addNeedle(needles, orderId, ORDER_ID, "order_id");

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("timestamp", Instant.now().toString());
    response.put("needles", needles);
    List<BackendLogItem> items =
        needles.isEmpty() ? List.of() : backendLogReader.recentMatchingLogLines(needles, safeLimit);
    List<String> traceIds =
        items.stream()
            .map(BackendLogItem::traceId)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .toList();
    response.put("items", items.stream().map(BackendLogItem::toMap).toList());
    response.put("lines", items.stream().map(BackendLogItem::displayLine).toList());
    response.put("traceIds", traceIds);
    if (!traceIds.isEmpty()) {
      response.put("traceId", traceIds.get(0));
    }
    return response;
  }

  @PostMapping("/warmup")
  Map<String, Object> warmup(
      @RequestParam(name = "count", defaultValue = "3") int count,
      @RequestHeader(name = "X-Demo-Control-Token", required = false) String controlToken) {
    requireControlToken(controlToken);
    int safeCount = Math.max(1, Math.min(count, 20));
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("count", safeCount);
    response.put(
        "items",
        java.util.stream.IntStream.rangeClosed(1, safeCount)
            .mapToObj(
                index -> {
                  try {
                    OrderRequest request = new OrderRequest("sku-1001", 1, 1999);
                    return restTemplate.postForObject(
                        orderUrl + "/api/orders", withLanguage(request), Map.class);
                  } catch (RuntimeException exception) {
                    Map<String, Object> failure = new LinkedHashMap<>();
                    failure.put("error", exception.getMessage());
                    return failure;
                  }
                })
            .toList());
    response.put("timestamp", Instant.now().toString());
    return response;
  }

  private Map<String, Object> forwardPost(String url) {
    try {
      Map<String, Object> body = restTemplate.postForObject(url, withLanguage(null), Map.class);
      return body == null ? Map.of("status", "UNKNOWN", "url", url) : body;
    } catch (RestClientException exception) {
      Map<String, Object> failure = new LinkedHashMap<>();
      failure.put("status", "DOWN");
      failure.put("url", url);
      failure.put("error", exception.getMessage());
      return failure;
    }
  }

  private Map<String, Object> disableBackendFaults() {
    Map<String, Object> results = new LinkedHashMap<>();
    results.put("order", forwardPost(orderUrl + "/admin/fault/off"));
    results.put("inventory", forwardPost(inventoryUrl + "/admin/fault/off"));
    results.put("payment", forwardPost(paymentUrl + "/admin/fault/off"));
    return results;
  }

  private Map<String, Object> serviceStatus(String baseUrl, boolean includeFaultMode) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("baseUrl", baseUrl);
    response.put("health", getJson(baseUrl + "/actuator/health"));
    if (includeFaultMode) {
      response.put("fault", getJson(baseUrl + "/admin/fault"));
    }
    return response;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getJson(String url) {
    try {
      ResponseEntity<Map> response =
          restTemplate.exchange(url, HttpMethod.GET, withLanguage(null), Map.class);
      Map<String, Object> body = (Map<String, Object>) response.getBody();
      return body == null ? Map.of("status", "UNKNOWN", "url", url) : body;
    } catch (RestClientException exception) {
      Map<String, Object> failure = new LinkedHashMap<>();
      failure.put("status", "DOWN");
      failure.put("url", url);
      failure.put("error", exception.getMessage());
      return failure;
    }
  }

  private HttpEntity<Object> withLanguage(Object body) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Demo-Language", DemoLanguage.current().code());
    return new HttpEntity<>(body, headers);
  }

  private void addNeedle(
      List<String> needles, String value, Pattern pattern, String parameterName) {
    if (value == null || value.isBlank()) {
      return;
    }
    String trimmed = value.trim();
    if (!pattern.matcher(trimmed).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + parameterName);
    }
    needles.add(trimmed);
  }

  private void requireControlToken(String presentedToken) {
    if (demoControlToken.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "demo control token is not configured");
    }
    byte[] expected = demoControlToken.getBytes(StandardCharsets.UTF_8);
    byte[] presented =
        (presentedToken == null ? "" : presentedToken).getBytes(StandardCharsets.UTF_8);
    if (!MessageDigest.isEqual(expected, presented)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid demo control token");
    }
  }

  private String trimTrailingSlash(String value) {
    String result = value;
    while (result.endsWith("/")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private FaultScenario faultScenario(String scenarioId) {
    return FAULT_SCENARIOS.stream()
        .filter(scenario -> scenario.id().equals(scenarioId))
        .findFirst()
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "unknown fault scenario: " + scenarioId));
  }

  private String adminUrl(String service) {
    return switch (service) {
      case "order-service" -> orderUrl;
      case "inventory-service" -> inventoryUrl;
      case "payment-service" -> paymentUrl;
      default ->
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "unsupported fault target: " + service);
    };
  }

  private Map<String, Object> activeFaults() {
    Map<String, Object> active = new LinkedHashMap<>();
    active.put("order", getJson(orderUrl + "/admin/fault"));
    active.put("inventory", getJson(inventoryUrl + "/admin/fault"));
    active.put("payment", getJson(paymentUrl + "/admin/fault"));
    return active;
  }
}

record FaultScenario(
    String id,
    String title,
    String layer,
    String kind,
    String service,
    String target,
    String mode,
    String description,
    long ttlSeconds,
    boolean clientSide) {
  Map<String, Object> toMap() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("id", id);
    response.put("title", title);
    response.put("layer", layer);
    response.put("kind", kind);
    response.put("service", service);
    response.put("target", target);
    response.put("mode", mode);
    response.put("description", description);
    response.put("ttlSeconds", ttlSeconds);
    response.put("clientSide", clientSide);
    return response;
  }
}
