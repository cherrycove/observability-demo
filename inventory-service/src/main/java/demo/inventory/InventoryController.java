package demo.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inventory")
class InventoryController {
  private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

  private final StringRedisTemplate redisTemplate;
  private final FaultState faultState;
  private final int timeoutSeconds;

  InventoryController(
      StringRedisTemplate redisTemplate,
      FaultState faultState,
      @Value("${fault.mode:none}") String initialFaultMode,
      @Value("${fault.redis-timeout-seconds:3}") int timeoutSeconds) {
    this.redisTemplate = redisTemplate;
    this.faultState = faultState;
    this.faultState.setMode(initialFaultMode);
    this.timeoutSeconds = timeoutSeconds;
  }

  @PostMapping("/reserve")
  Map<String, Object> reserve(
      @RequestBody(required = false) InventoryRequest request,
      @RequestHeader(value = "X-Key-Request", required = false) String keyRequest,
      @RequestHeader(value = "X-Business-Request-Id", required = false) String businessRequestId,
      @RequestHeader(value = "baggage", required = false) String baggage) {
    InventoryRequest inventoryRequest =
        request == null ? new InventoryRequest("unknown", "sku-1001", 1) : request.withDefaults();
    RequestMetadata metadata = RequestMetadata.from(keyRequest, businessRequestId, baggage);
    log.info(
        "预留库存：订单ID={} 商品={} 数量={} 库存模式={} 关键请求={} 业务请求ID={} 上下文={}",
        inventoryRequest.orderId(),
        inventoryRequest.sku(),
        inventoryRequest.quantity(),
        faultState.getMode(),
        metadata.keyRequestOrDash(),
        metadata.businessRequestIdOrDash(),
        metadata.baggageOrDash());

    FaultSnapshot fault = faultState.current();
    if (fault.is("redis_timeout")) {
      fault.applyCurrentSpanTags();
      injectRedisTimeout(inventoryRequest, metadata, fault);
    }

    String stockKey = "stock:" + inventoryRequest.sku();
    Long remaining = redisTemplate.opsForValue().decrement(stockKey, inventoryRequest.quantity());
    if (remaining == null) {
      log.warn(
          "库存预留失败：订单ID={} 商品={} 原因=Redis 返回空库存值 关键请求={} 业务请求ID={}",
          inventoryRequest.orderId(),
          inventoryRequest.sku(),
          metadata.keyRequestOrDash(),
          metadata.businessRequestIdOrDash());
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "redis returned null stock value");
    }
    if (remaining < 0) {
      redisTemplate.opsForValue().increment(stockKey, inventoryRequest.quantity());
      log.warn(
          "库存预留失败：订单ID={} 商品={} 请求数量={} 剩余库存不足 关键请求={} 业务请求ID={}",
          inventoryRequest.orderId(),
          inventoryRequest.sku(),
          inventoryRequest.quantity(),
          metadata.keyRequestOrDash(),
          metadata.businessRequestIdOrDash());
      throw new ResponseStatusException(HttpStatus.CONFLICT, "stock is not enough");
    }

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("orderId", inventoryRequest.orderId());
    response.put("sku", inventoryRequest.sku());
    response.put("reserved", inventoryRequest.quantity());
    response.put("remaining", remaining);
    response.put("mode", faultState.getMode());
    response.put("keyRequest", metadata.keyRequest());
    response.put("businessRequestId", metadata.businessRequestId());
    response.put("reservedAt", Instant.now().toString());
    return response;
  }

  private void injectRedisTimeout(
      InventoryRequest request, RequestMetadata metadata, FaultSnapshot fault) {
    log.warn(
        "故障注入：模拟 Redis 超时 订单ID={} 故障ID={} 层级={} 目标={} 等待秒数={} 关键请求={} 业务请求ID={}",
        request.orderId(),
        fault.mode(),
        fault.layer(),
        fault.target(),
        timeoutSeconds,
        metadata.keyRequestOrDash(),
        metadata.businessRequestIdOrDash());
    redisTemplate.opsForList().rightPop("fault:empty-list", Duration.ofSeconds(timeoutSeconds));
    throw new ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "simulated redis timeout in inventory-service");
  }

  @GetMapping("/stock")
  Map<String, Object> stock() {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("sku", "sku-1001");
    response.put("stock", redisTemplate.opsForValue().get("stock:sku-1001"));
    response.put("mode", faultState.getMode());
    return response;
  }
}
