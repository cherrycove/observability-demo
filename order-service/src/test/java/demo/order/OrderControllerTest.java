package demo.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class OrderControllerTest {
  @TempDir private Path tempDir;

  private final RecordingRestTemplate restTemplate = new RecordingRestTemplate();
  private final RecordingOrderStore orderStore = new RecordingOrderStore();
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(
              new OrderController(
                  restTemplate,
                  "http://inventory-service.test",
                  "http://payment-service.test",
                  new FaultState(),
                  orderStore,
                  1600))
          .build();

  @Test
  void demoOrderConfirmsOrderAndReturnsBusinessFields() throws Exception {
    mockMvc
        .perform(
            get("/api/orders/demo")
                .header("X-Key-Request", "checkout_submit_order")
                .header("X-Business-Request-Id", "biz-1001")
                .header("baggage", "biz_chain=selfheal_checkout"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.sku").value("sku-1001"))
        .andExpect(jsonPath("$.quantity").value(1))
        .andExpect(jsonPath("$.keyRequest").value("checkout_submit_order"))
        .andExpect(jsonPath("$.businessRequestId").value("biz-1001"));

    assertThat(restTemplate.calledUrls)
        .containsExactly(
            "http://inventory-service.test/api/inventory/reserve",
            "http://payment-service.test/api/payments/pay");
    assertThat(orderStore.statuses).containsExactly("CREATED", "INVENTORY_RESERVED", "CONFIRMED");
  }

  @Test
  void demoOrderPreservesProjectBaggageForDownstreamServices() throws Exception {
    mockMvc
        .perform(
            get("/api/orders/demo")
                .header("X-Key-Request", "checkout_submit_order")
                .header("X-Business-Request-Id", "biz-1001")
                .header(
                    "baggage",
                    "project=mall-demo,"
                        + "key_request=checkout_submit_order,"
                        + "biz_chain=selfheal_checkout,biz_request_id=biz-1001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"));

    assertThat(restTemplate.requests).hasSize(2);
    assertThat(restTemplate.requests)
        .allSatisfy(
            request -> {
              String baggage = request.getHeaders().getFirst("baggage");
              assertThat(baggage)
                  .contains("project=mall-demo")
                  .contains("key_request=checkout_submit_order")
                  .contains("biz_chain=selfheal_checkout")
                  .contains("biz_request_id=biz-1001");
              assertThat(baggage).matches("^[\\x21-\\x7E]+$");
            });
  }

  @Test
  void logsEndpointMatchesBackendLogLinesByBusinessRequest() throws Exception {
    String businessRequestId = "biz-1001-abcdef";
    String orderId = "ord-1001-abcdef";
    Files.writeString(
        tempDir.resolve("gateway-service.log"),
        "2026-06-20 03:13:19.150 INFO [main] demo.gateway.GatewayProxyFilter - 网关接入：方法=GET 路径=/api/orders/demo 下游=http://order-service:8080/api/orders/demo 关键请求=checkout_submit_order 业务请求ID=%s | service=gateway-service env=test version=1.0.0 project=mall-demo trace_id=1057687758430268391 span_id=2206721340737204861 process_id=1 host=demo-node pod_name=gateway-service-abc pod_namespace=demo container_name=gateway-service%n"
            .formatted(businessRequestId));
    Files.writeString(
        tempDir.resolve("order-service.log"),
        "2026-06-20 03:13:19.165 INFO [main] demo.order.OrderController - 创建订单：订单ID=%s 商品=sku-1001 数量=1 金额=1999分 关键请求=checkout_submit_order 业务请求ID=%s | service=order-service env=test version=1.0.0 project=mall-demo trace_id=1057687758430268391 span_id=4456721340737204861 process_id=1 host_process_id=101 container_process_id=1 host=demo-node host_name=order-service-abc pod_name=order-service-abc pod_namespace=demo container_name=order-service container_id=container-order-abc%n"
            .formatted(orderId, businessRequestId));
    Files.writeString(
        tempDir.resolve("inventory-service.log"),
        "2026-06-20 03:13:19.172 INFO [main] demo.inventory.InventoryController - 预留库存：订单ID=%s 商品=sku-1001 数量=1 库存模式=none 关键请求=checkout_submit_order 业务请求ID=%s | service=inventory-service env=test version=1.0.0 project=mall-demo trace_id=1057687758430268391 span_id=9906721340737204861 process_id=1 host_process_id=102 container_process_id=1 host=demo-node host_name=inventory-service-abc pod_name=inventory-service-abc pod_namespace=demo container_name=inventory-service container_id=container-inventory-abc%n"
            .formatted(orderId, businessRequestId));
    Files.writeString(
        tempDir.resolve("payment-service.log"),
        "2026-06-20 03:13:19.330 INFO [main] demo.payment.PaymentController - 支付成功：订单ID=%s 金额=1999分 耗时=120ms 关键请求=checkout_submit_order 业务请求ID=%s | service=payment-service env=test version=1.0.0 project=mall-demo trace_id=1057687758430268391 span_id=1206721340737204861 process_id=1 host_process_id=103 container_process_id=1 host=demo-node host_name=payment-service-abc pod_name=payment-service-abc pod_namespace=demo container_name=payment-service container_id=container-payment-abc%n"
            .formatted(orderId, businessRequestId));

    MockMvc logMvc = MockMvcBuilders.standaloneSetup(newDemoController()).build();

    logMvc
        .perform(
            get("/api/demo/logs")
                .param("biz_request_id", businessRequestId)
                .param("order_id", orderId)
                .param("limit", "8"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(4))
        .andExpect(jsonPath("$.items[0].service").value("gateway-service"))
        .andExpect(jsonPath("$.items[1].service").value("order-service"))
        .andExpect(jsonPath("$.items[2].service").value("inventory-service"))
        .andExpect(jsonPath("$.items[3].service").value("payment-service"))
        .andExpect(jsonPath("$.traceId").value("1057687758430268391"))
        .andExpect(jsonPath("$.items[0].traceId").value("1057687758430268391"))
        .andExpect(
            jsonPath("$.items[3].message").value(org.hamcrest.Matchers.containsString("支付成功")));
  }

  @Test
  void logsEndpointRejectsBroadNeedles() throws Exception {
    Files.writeString(
        tempDir.resolve("order-service.log"),
        "2026-06-20 03:13:19.165 INFO demo.order.OrderController 创建订单%n");

    MockMvc logMvc = MockMvcBuilders.standaloneSetup(newDemoController()).build();

    logMvc
        .perform(get("/api/demo/logs").param("biz_request_id", "INFO").param("limit", "8"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rumConfigUsesRuntimeTraceEnvironment() throws Exception {
    MockMvc logMvc =
        MockMvcBuilders.standaloneSetup(newDemoController("staging", "v1.2.3", "business-web"))
            .build();

    logMvc
        .perform(get("/api/demo/rum-config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.env").value("staging"))
        .andExpect(jsonPath("$.version").value("v1.2.3"))
        .andExpect(jsonPath("$.service").value("business-web"))
        .andExpect(jsonPath("$.project").value("mall-demo"))
        .andExpect(jsonPath("$.applicationId").value("order_web_docker_demo"))
        .andExpect(jsonPath("$.enabled").value(true))
        .andExpect(jsonPath("$.clientToken").doesNotExist())
        .andExpect(jsonPath("$.site").doesNotExist())
        .andExpect(jsonPath("$.datakitOrigin").value("/rum-proxy"))
        .andExpect(jsonPath("$.baggageKeys[0]").value("project"));
  }

  @Test
  void storefrontEmbedsBookCoversForSessionReplay() throws Exception {
    byte[] sourceBytes;
    try (var source = getClass().getResourceAsStream("/static/assets/selfheal-i18n.js")) {
      assertThat(source).isNotNull();
      sourceBytes = source.readAllBytes();
    }

    String source = new String(sourceBytes, StandardCharsets.UTF_8);
    assertThat(source)
        .contains("data:image/svg+xml")
        .contains("cover: bookCovers.zh")
        .contains("cover: bookCovers.en")
        .doesNotContain("cover: 'assets/observability-engineering-");
  }

  @Test
  void publicConfigContainsNoSecretsAndOmitsUnconfiguredWorkspace() throws Exception {
    MockMvc demoMvc = MockMvcBuilders.standaloneSetup(newDemoController()).build();

    demoMvc
        .perform(get("/api/demo/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.controlTokenRequired").value(true))
        .andExpect(jsonPath("$.rumEnabled").value(true))
        .andExpect(jsonPath("$.project").value("mall-demo"))
        .andExpect(jsonPath("$.observabilityConsoleUrl").doesNotExist())
        .andExpect(jsonPath("$.workspaceId").doesNotExist())
        .andExpect(jsonPath("$.controlToken").doesNotExist());
  }

  @Test
  void publicConfigUsesTrueWatchAp1ConsoleWhenWorkspaceIsConfigured() throws Exception {
    MockMvc demoMvc =
        MockMvcBuilders.standaloneSetup(
                newDemoController(
                    new RestTemplate(),
                    "test",
                    "1.0.0",
                    "mall-h5",
                    "https://ap1-console.truewatch.com/",
                    "workspace-demo"))
            .build();

    demoMvc
        .perform(get("/api/demo/config"))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.observabilityConsoleUrl").value("https://ap1-console.truewatch.com"))
        .andExpect(jsonPath("$.workspaceId").value("workspace-demo"));
  }

  @Test
  void faultCatalogListsMultiLayerScenarios() throws Exception {
    RecordingRestTemplate demoRestTemplate = new RecordingRestTemplate();
    MockMvc demoMvc = MockMvcBuilders.standaloneSetup(newDemoController(demoRestTemplate)).build();

    demoMvc
        .perform(get("/api/demo/faults"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(8))
        .andExpect(jsonPath("$.items[0].id").value("frontend_click_error"))
        .andExpect(jsonPath("$.items[0].service").value("mall-h5"))
        .andExpect(jsonPath("$.items[2].id").value("frontend_sourcemap_error"))
        .andExpect(jsonPath("$.items[4].id").value("inventory_redis_timeout"));
  }

  @Test
  void enableFaultScenarioForwardsToTargetService() throws Exception {
    RecordingRestTemplate demoRestTemplate = new RecordingRestTemplate();
    MockMvc demoMvc = MockMvcBuilders.standaloneSetup(newDemoController(demoRestTemplate)).build();

    demoMvc
        .perform(
            post("/api/demo/faults/inventory_redis_timeout/enable")
                .header("X-Demo-Control-Token", "demo-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scenario.id").value("inventory_redis_timeout"))
        .andExpect(jsonPath("$.result.mode").value("redis_timeout"));

    assertThat(demoRestTemplate.postObjectUrls)
        .contains("http://inventory-service.test/admin/fault/redis_timeout?ttlSeconds=300");
  }

  @Test
  void faultControlsRequireSharedToken() throws Exception {
    MockMvc demoMvc =
        MockMvcBuilders.standaloneSetup(newDemoController(new RecordingRestTemplate())).build();

    demoMvc
        .perform(post("/api/demo/faults/payment_error/enable"))
        .andExpect(status().isUnauthorized());
    demoMvc
        .perform(post("/api/demo/faults/off").header("X-Demo-Control-Token", "wrong-token"))
        .andExpect(status().isUnauthorized());
    demoMvc
        .perform(post("/api/demo/warmup").header("X-Demo-Control-Token", "demo-token"))
        .andExpect(status().isOk());
  }

  @Test
  void rumReplayProxyForwardsOnlyWhenRumIsEnabled() throws Exception {
    RestTemplate proxyTemplate = new RestTemplate();
    MockRestServiceServer server = MockRestServiceServer.bindTo(proxyTemplate).build();
    server
        .expect(requestTo("http://datakit.test:9529/v1/write/rum/replay?batch=1"))
        .andExpect(method(HttpMethod.POST))
        .andRespond(withSuccess("accepted", MediaType.TEXT_PLAIN));
    MockMvc proxyMvc =
        MockMvcBuilders.standaloneSetup(
                new RumProxyController(proxyTemplate, "http://datakit.test:9529/", true))
            .build();

    proxyMvc
        .perform(
            post("/rum-proxy/v1/write/rum/replay")
                .queryParam("batch", "1")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .content("replay-payload"))
        .andExpect(status().isOk());
    server.verify();

    MockMvc disabledProxyMvc =
        MockMvcBuilders.standaloneSetup(
                new RumProxyController(new RestTemplate(), "http://datakit.test:9529", false))
            .build();
    disabledProxyMvc.perform(post("/rum-proxy/v1/write/rum")).andExpect(status().isNotFound());
    disabledProxyMvc.perform(post("/rum-proxy/actuator/health")).andExpect(status().isNotFound());
  }

  @Test
  void canEnableAndDisableOrderFault() throws Exception {
    FaultState faultState = new FaultState();
    MockMvc faultMvc =
        MockMvcBuilders.standaloneSetup(new FaultAdminController(faultState)).build();

    faultMvc
        .perform(post("/admin/fault/order-slow"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("order_slow"))
        .andExpect(jsonPath("$.layer").value("service"));

    faultMvc
        .perform(get("/admin/fault"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("order_slow"));

    faultMvc
        .perform(post("/admin/fault/off"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("none"));
  }

  @Test
  void orderFaultExpiresAfterTtl() {
    AtomicReference<java.time.Instant> now =
        new AtomicReference<>(java.time.Instant.parse("2026-07-14T00:00:00Z"));
    FaultState state = new FaultState(now::get);
    state.enable("order_slow", 30);
    assertThat(state.current().mode()).isEqualTo("order_slow");

    now.set(now.get().plusSeconds(31));
    assertThat(state.current().mode()).isEqualTo("none");
  }

  private DemoController newDemoController() {
    return newDemoController(new RestTemplate(), "test", "1.0.0", "mall-h5");
  }

  private DemoController newDemoController(RestTemplate restTemplate) {
    return newDemoController(restTemplate, "test", "1.0.0", "mall-h5");
  }

  private DemoController newDemoController(String rumEnv, String rumVersion, String rumService) {
    return newDemoController(new RestTemplate(), rumEnv, rumVersion, rumService);
  }

  private DemoController newDemoController(
      RestTemplate restTemplate, String rumEnv, String rumVersion, String rumService) {
    return newDemoController(
        restTemplate,
        rumEnv,
        rumVersion,
        rumService,
        "https://ap1-console.truewatch.com",
        "");
  }

  private DemoController newDemoController(
      RestTemplate restTemplate,
      String rumEnv,
      String rumVersion,
      String rumService,
      String consoleUrl,
      String workspaceId) {
    return new DemoController(
        restTemplate,
        "http://order-service.test",
        "http://inventory-service.test",
        "http://payment-service.test",
        "demo-token",
        "mall-demo",
        true,
        "order_web_docker_demo",
        "/rum-proxy",
        rumEnv,
        rumVersion,
        rumService,
        consoleUrl,
        workspaceId,
        tempDir.toString(),
        false,
        240,
        600);
  }

  private static final class RecordingRestTemplate extends RestTemplate {
    private final List<String> calledUrls = new ArrayList<>();
    private final List<String> postObjectUrls = new ArrayList<>();
    private final List<HttpEntity<?>> requests = new ArrayList<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T> ResponseEntity<T> postForEntity(
        String url, @Nullable Object request, Class<T> responseType, Object... uriVariables)
        throws RestClientException {
      calledUrls.add(url);
      if (request instanceof HttpEntity<?> entity) {
        requests.add(entity);
      }
      if (url.startsWith("http://inventory-service.test")) {
        return (ResponseEntity<T>) ResponseEntity.ok(Map.of("status", "RESERVED"));
      }
      if (url.startsWith("http://payment-service.test")) {
        return (ResponseEntity<T>) ResponseEntity.ok(Map.of("status", "PAID"));
      }
      throw new RestClientException("unexpected downstream url: " + url);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getForObject(String url, Class<T> responseType, Object... uriVariables)
        throws RestClientException {
      if (url.endsWith("/actuator/health")) {
        return (T) Map.of("status", "UP");
      }
      if (url.endsWith("/admin/fault")) {
        return (T) Map.of("mode", "none", "layer", "normal", "service", serviceName(url));
      }
      throw new RestClientException("unexpected get url: " + url);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T postForObject(
        String url, @Nullable Object request, Class<T> responseType, Object... uriVariables)
        throws RestClientException {
      postObjectUrls.add(url);
      if (url.contains("/admin/fault/off")) {
        return (T) Map.of("mode", "none");
      }
      if (url.contains("/admin/fault/")) {
        String mode = url.substring(url.lastIndexOf('/') + 1);
        int queryIndex = mode.indexOf('?');
        if (queryIndex >= 0) {
          mode = mode.substring(0, queryIndex);
        }
        return (T) Map.of("mode", mode.replace('-', '_'));
      }
      throw new RestClientException("unexpected post url: " + url);
    }

    private String serviceName(String url) {
      if (url.contains("inventory")) {
        return "inventory-service";
      }
      if (url.contains("payment")) {
        return "payment-service";
      }
      return "order-service";
    }
  }

  private static final class RecordingOrderStore implements OrderStore {
    private final List<String> statuses = new ArrayList<>();

    @Override
    public void create(
        String orderId,
        OrderRequest request,
        RequestMetadata metadata,
        java.time.Instant createdAt) {
      statuses.add("CREATED");
    }

    @Override
    public void updateStatus(String orderId, String status, java.time.Instant updatedAt) {
      statuses.add(status);
    }
  }
}
