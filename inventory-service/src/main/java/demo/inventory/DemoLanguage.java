package demo.inventory;

import java.util.Locale;
import org.slf4j.MDC;

enum DemoLanguage {
  ZH("zh"),
  EN("en");

  private final String code;

  DemoLanguage(String code) {
    this.code = code;
  }

  static DemoLanguage from(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    return normalized.equals("en") || normalized.startsWith("en-") ? EN : ZH;
  }

  static DemoLanguage current() {
    return from(MDC.get("language"));
  }

  String code() {
    return code;
  }

  String text(String chinese, String english) {
    return this == EN ? english : chinese;
  }
}
