package demo.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
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
        .andExpect(header("X-Gateway-Service", "gateway-service"))
        .andRespond(
            withSuccess("{\"status\":\"CONFIRMED\"}", MediaType.APPLICATION_JSON)
                .header("ext_trace_id", "downstream-trace"));

    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
    request.setQueryString("source=shop");
    request.addHeader("Content-Type", "application/json");
    request.addHeader("X-Key-Request", "checkout_submit_order");
    request.addHeader("X-Business-Request-Id", "biz-gateway-1001");
    request.setContent("{\"sku\":\"sku-1001\"}".getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();

    new GatewayProxyFilter(restTemplate, "http://order-service.test/")
        .doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader("X-Gateway-Service")).isEqualTo("gateway-service");
    assertThat(response.getHeader("ext_trace_id")).isNull();
    assertThat(response.getContentAsString()).contains("CONFIRMED");
    server.verify();
  }
}
