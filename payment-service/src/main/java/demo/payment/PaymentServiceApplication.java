package demo.payment;



import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class PaymentServiceApplication {
  public static void main(String[] args) {
    SpringApplication.run(PaymentServiceApplication.class, args);
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
