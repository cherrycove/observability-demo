package demo.order;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin/fault")
class FaultAdminController {
  private static final Logger log = LoggerFactory.getLogger(FaultAdminController.class);

  private final FaultState faultState;

  FaultAdminController(FaultState faultState) {
    this.faultState = faultState;
  }

  @GetMapping
  Map<String, Object> current() {
    return faultState.current().toMap("order-service");
  }

  @PostMapping("/{mode}")
  Map<String, Object> enable(
      @PathVariable("mode") String mode,
      @RequestHeader(value = "X-Demo-Language", required = false) String languageHeader,
      @RequestParam(name = "ttlSeconds", defaultValue = "300") long ttlSeconds) {
    FaultSnapshot fault = faultState.enable(mode, ttlSeconds);
    DemoLanguage language = DemoLanguage.from(languageHeader);
    log.info(
        language.text(
            "故障开关：已开启订单服务故障 mode={} layer={} target={} ttlSeconds={}",
            "Fault enabled for order-service: mode={} layer={} target={} ttl_seconds={}"),
        fault.mode(),
        fault.layer(),
        fault.target(),
        fault.ttlSeconds());
    return fault.toMap("order-service");
  }

  @PostMapping("/off")
  Map<String, Object> off(
      @RequestHeader(value = "X-Demo-Language", required = false) String languageHeader) {
    FaultSnapshot fault = faultState.disable();
    DemoLanguage language = DemoLanguage.from(languageHeader);
    log.info(
        language.text(
            "故障恢复：已关闭订单服务故障", "Fault recovered: order-service fault disabled"));
    return fault.toMap("order-service");
  }
}

@Component
class FaultState {
  private static final FaultProfile NONE =
      new FaultProfile("none", "normal", "none", "order-service", "故障已关闭", 0);
  private static final Map<String, FaultProfile> PROFILES =
      Map.of(
          NONE.mode(),
          NONE,
          "order_slow",
          new FaultProfile(
              "order_slow",
              "service",
              "latency",
              "order-service",
              "订单入口慢响应，展示入口服务慢 Span 和 RUM Resource 慢加载。",
              300));

  private volatile ActiveFault activeFault = ActiveFault.none(NONE);
  private final java.util.function.Supplier<Instant> nowSupplier;

  FaultState() {
    this(Instant::now);
  }

  FaultState(java.util.function.Supplier<Instant> nowSupplier) {
    this.nowSupplier = nowSupplier;
  }

  FaultSnapshot current() {
    ActiveFault active = activeFault;
    if (!active.profile().mode().equals("none")
        && active.expiresAt() != null
        && !nowSupplier.get().isBefore(active.expiresAt())) {
      activeFault = ActiveFault.none(NONE);
      active = activeFault;
    }
    return active.toSnapshot();
  }

  FaultSnapshot enable(String mode, long ttlSeconds) {
    String normalized = normalize(mode);
    FaultProfile profile = PROFILES.get(normalized);
    if (profile == null || "none".equals(normalized)) {
      if ("none".equals(normalized)) {
        return disable();
      }
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "unsupported order fault mode: " + mode);
    }
    long safeTtl =
        Math.max(30, Math.min(ttlSeconds <= 0 ? profile.defaultTtlSeconds() : ttlSeconds, 1800));
    Instant now = nowSupplier.get();
    activeFault = new ActiveFault(profile, now, now.plusSeconds(safeTtl));
    return activeFault.toSnapshot();
  }

  FaultSnapshot disable() {
    activeFault = ActiveFault.none(NONE);
    return activeFault.toSnapshot();
  }

  private String normalize(String mode) {
    return mode == null || mode.isBlank()
        ? "none"
        : mode.trim().replace('-', '_').toLowerCase(Locale.ROOT);
  }
}

record FaultProfile(
    String mode,
    String layer,
    String kind,
    String target,
    String message,
    long defaultTtlSeconds) {}

record ActiveFault(FaultProfile profile, Instant startedAt, Instant expiresAt) {
  static ActiveFault none(FaultProfile none) {
    return new ActiveFault(none, null, null);
  }

  FaultSnapshot toSnapshot() {
    return new FaultSnapshot(
        profile.mode(),
        profile.layer(),
        profile.kind(),
        profile.target(),
        profile.message(),
        startedAt,
        expiresAt);
  }
}

record FaultSnapshot(
    String mode,
    String layer,
    String kind,
    String target,
    String message,
    Instant startedAt,
    Instant expiresAt) {
  boolean is(String expectedMode) {
    return mode.equalsIgnoreCase(expectedMode);
  }

  long ttlSeconds() {
    if (expiresAt == null) {
      return 0;
    }
    return Math.max(0, Duration.between(Instant.now(), expiresAt).toSeconds());
  }

  Map<String, Object> toMap(String service) {
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("service", service);
    response.put("mode", mode);
    response.put("layer", layer);
    response.put("kind", kind);
    response.put("target", target);
    response.put("message", message);
    response.put("startedAt", startedAt == null ? null : startedAt.toString());
    response.put("expiresAt", expiresAt == null ? null : expiresAt.toString());
    response.put("ttlSeconds", ttlSeconds());
    response.put("updatedAt", Instant.now().toString());
    return response;
  }

  void applyCurrentSpanTags() {
    MDC.put("fault_id", mode);
    MDC.put("fault_layer", layer);
    MDC.put("fault_kind", kind);
    MDC.put("fault_target", target);
    try {
      Class<?> tracerClass =
          Class.forName("datadog.trace.bootstrap.instrumentation.api.AgentTracer");
      Object activeSpan = tracerClass.getMethod("activeSpan").invoke(null);
      applySpanTags(activeSpan);
    } catch (ReflectiveOperationException | LinkageError ignored) {
      // dd-java-agent is not available; keep application behavior unchanged.
    }
  }

  private void applySpanTags(Object span) throws ReflectiveOperationException {
    if (span == null || !Boolean.TRUE.equals(span.getClass().getMethod("isValid").invoke(span))) {
      return;
    }
    setTags(span);
    Object localRoot = span.getClass().getMethod("getLocalRootSpan").invoke(span);
    if (localRoot != null
        && Boolean.TRUE.equals(localRoot.getClass().getMethod("isValid").invoke(localRoot))) {
      setTags(localRoot);
    }
  }

  private void setTags(Object span) throws ReflectiveOperationException {
    ProcessIdentity.setTags(span);
    span.getClass().getMethod("setTag", String.class, String.class).invoke(span, "fault_id", mode);
    span.getClass()
        .getMethod("setTag", String.class, String.class)
        .invoke(span, "fault_layer", layer);
    span.getClass()
        .getMethod("setTag", String.class, String.class)
        .invoke(span, "fault_kind", kind);
    span.getClass()
        .getMethod("setTag", String.class, String.class)
        .invoke(span, "fault_target", target);
  }
}
