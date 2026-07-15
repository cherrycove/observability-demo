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
              "demo-infra-monitor",
              "demo-logs",
              "demo-log-long-retention",
              "demo-network",
              "demo-apm-llm",
              "demo-synthetic",
              "demo-rum",
              "demo-session-replay",
              "demo-task-call",
              "demo-report",
              "demo-sensitive-scan",
              "demo-data-forward")) {
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
