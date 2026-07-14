package demo.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FaultAdminControllerTest {
  private final FaultState faultState = new FaultState();
  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new FaultAdminController(faultState)).build();

  @Test
  void canEnableAndDisableRedisTimeoutFault() throws Exception {
    mockMvc
        .perform(post("/admin/fault/redis-timeout"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("redis_timeout"))
        .andExpect(jsonPath("$.layer").value("dependency"));

    mockMvc
        .perform(get("/admin/fault"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("redis_timeout"));

    mockMvc
        .perform(post("/admin/fault/off"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("none"));
  }

  @Test
  void canEnableRedisTimeoutFaultThroughGenericModeEndpoint() throws Exception {
    mockMvc
        .perform(post("/admin/fault/redis_timeout"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.mode").value("redis_timeout"))
        .andExpect(jsonPath("$.target").value("redis"));
  }

  @Test
  void redisFaultExpiresAfterTtl() {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-07-14T00:00:00Z"));
    FaultState state = new FaultState(now::get);
    state.enable("redis_timeout", 30);
    assertThat(state.current().mode()).isEqualTo("redis_timeout");

    now.set(now.get().plusSeconds(31));
    assertThat(state.current().mode()).isEqualTo("none");
  }
}
