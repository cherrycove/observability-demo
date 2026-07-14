package demo.inventory;

import java.util.List;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class InventoryServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(InventoryServiceApplication.class, args);
  }

  @Bean
  CommandLineRunner seedStock(StringRedisTemplate redisTemplate) {
    return args -> {
      for (String sku :
          List.of(
              "sku-1001",
              "guance-infra-monitor",
              "guance-logs",
              "guance-log-long-retention",
              "guance-network",
              "guance-apm-llm",
              "guance-synthetic",
              "guance-rum",
              "guance-session-replay",
              "guance-task-call",
              "guance-report",
              "guance-sensitive-scan",
              "guance-data-forward")) {
        String key = "stock:" + sku;
        Boolean exists = redisTemplate.hasKey(key);
        if (!Boolean.TRUE.equals(exists)) {
          redisTemplate.opsForValue().set(key, "100000");
        }
      }
    };
  }

  @Bean
  WebMvcConfigurer keyRequestTagConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry
            .addInterceptor(new KeyRequestSpanTagInterceptor())
            .addPathPatterns("/api/**", "/admin/**", "/actuator/**");
      }
    };
  }
}
