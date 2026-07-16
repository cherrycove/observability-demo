package demo.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class GatewayProxyFilterTest {
  @Test
  void rejectsInternalAdminRoutesWithoutCallingDownstream() throws Exception {
    RestTemplate restTemplate = new RestTemplate();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/fault/off");
    MockHttpServletResponse response = new MockHttpServletResponse();

    new GatewayProxyFilter(restTemplate, "http://order-service.test")
        .doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(404);
  }

  @Test
  void forwardsOrderRequestAndBusinessHeaders() throws Exception {
    RestTemplate restTemplate = new RestTemplate();
    restTemplate.setErrorHandler(new PassthroughResponseErrorHandler());
    MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
    server
        .expect(requestTo("http://order-service.test/api/orders?source=shop"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("X-Key-Request", "checkout_submit_order"))
        .andExpect(header("X-Business-Request-Id", "biz-gateway-1001"))
        .andExpect(header("X-Demo-Language", "en"))
        .andExpect(header("X-Gateway-Service", "gateway-service"))
        .andRespond(
            withSuccess("{\"status\":\"CONFIRMED\"}", MediaType.APPLICATION_JSON)
                .header("ext_trace_id", "downstream-trace"));

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
    request.setQueryString("source=shop");
    request.addHeader("Content-Type", "application/json");
    request.addHeader("X-Key-Request", "checkout_submit_order");
    request.addHeader("X-Business-Request-Id", "biz-gateway-1001");
    request.addHeader("X-Demo-Language", "en");
    request.setContent("{\"sku\":\"sku-1001\"}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();
    Logger logger = (Logger) LoggerFactory.getLogger(GatewayProxyFilter.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);

    try {
      new GatewayProxyFilter(restTemplate, "http://order-service.test/")
          .doFilter(request, response, new MockFilterChain());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-Gateway-Service")).isEqualTo("gateway-service");
    assertThat(response.getHeader("ext_trace_id")).isNull();
    assertThat(response.getContentAsString()).contains("CONFIRMED");
    assertThat(appender.list)
        .anySatisfy(
            event -> {
              assertThat(event.getFormattedMessage()).contains("Gateway request received");
              assertThat(event.getMDCPropertyMap()).containsEntry("language", "en");
            });
    server.verify();
  }
}
