package demo.payment;



import org.slf4j.MDC;

record RequestMetadata(String keyRequest, String businessRequestId, String baggage) {
  static RequestMetadata from(String keyRequest, String businessRequestId, String baggage) {
    return new RequestMetadata(
        blankToNull(keyRequest), blankToNull(businessRequestId), blankToNull(baggage));
  }

  String keyRequestOrDash() {
    return keyRequest == null ? "-" : keyRequest;
  }

  String businessRequestIdOrDash() {
    return businessRequestId == null ? "-" : businessRequestId;
  }

  String baggageOrDash() {
    return baggage == null ? "-" : baggage;
  }

  void applyCurrentSpanTags() {
    if (keyRequest != null) {
      MDC.put("key_request", keyRequest);
    }
    if (businessRequestId != null) {
      MDC.put("biz_request_id", businessRequestId);
    }
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
    if (keyRequest != null) {
      span.getClass()
          .getMethod("setTag", String.class, String.class)
          .invoke(span, "key_request", keyRequest);
      span.getClass()
          .getMethod("setBaggageItem", String.class, String.class)
          .invoke(span, "key_request", keyRequest);
    }
    if (businessRequestId != null) {
      span.getClass()
          .getMethod("setTag", String.class, String.class)
          .invoke(span, "biz_request_id", businessRequestId);
      span.getClass()
          .getMethod("setBaggageItem", String.class, String.class)
          .invoke(span, "biz_request_id", businessRequestId);
    }
    String bizChain = baggageValue("biz_chain");
    if (bizChain != null) {
      span.getClass()
          .getMethod("setTag", String.class, String.class)
          .invoke(span, "biz_chain", bizChain);
      span.getClass()
          .getMethod("setBaggageItem", String.class, String.class)
          .invoke(span, "biz_chain", bizChain);
    }
  }

  private String baggageValue(String key) {
    if (baggage == null) {
      return null;
    }
    for (String item : baggage.split(",")) {
      String[] parts = item.trim().split("=", 2);
      if (parts.length == 2 && key.equals(parts[0].trim())) {
        return parts[1].trim();
      }
    }
    return null;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}

record PaymentRequest(String orderId, Integer amountCent) {
  PaymentRequest withDefaults() {
    return new PaymentRequest(
        orderId == null || orderId.isBlank() ? "unknown" : orderId,
        amountCent == null || amountCent < 1 ? 1999 : amountCent);
  }
}
