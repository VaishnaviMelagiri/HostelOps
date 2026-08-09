package com.hostelops.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.hostelops.security.JwtAuthFilter;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the HTTP contract of {@code GET /api/health} without needing a running database.
 *
 * <p>{@code @WebMvcTest} starts a cut-down Spring context containing only the web layer - the
 * controller, JSON serialisation and routing - and no JPA, no DataSource, no Flyway. That is why
 * this test runs in a second or two and why {@code ./mvnw test} passes before Docker is even
 * started. The database itself gets tested for real from Phase 4 using Testcontainers.
 *
 * <p>{@code @MockitoBean} puts a stub {@link HealthService} into that context, so we control what
 * the service returns and can assert purely on the translation to HTTP.
 *
 * <p>{@code addFilters = false} switches off the Spring Security filter chain for this slice.
 * Without it, adding spring-boot-starter-security in Phase 2 would make every test here fail with
 * 401 - not because the controller broke, but because a slice test does not load SecurityConfig and
 * therefore does not know {@code /api/health} is public. Security is a separate concern with its
 * own coverage in AuthFlowTest, which exercises the real filter chain end to end.
 */
@WebMvcTest(
        controllers = HealthController.class,
        // @WebMvcTest picks up every Filter bean, which from Phase 2 includes JwtAuthFilter - and
        // that would drag JwtService, TokenRevocationService and AppUserDetailsService into a slice
        // that has nothing to do with them, failing the context before a test even runs. Excluding
        // it keeps this test about the health endpoint. (addFilters = false below stops filters
        // running; this stops them being *created*, which is the part that broke.)
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE, classes = JwtAuthFilter.class))
@AutoConfigureMockMvc(addFilters = false)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;   // sends fake HTTP requests straight at the controller, no real port

    @MockitoBean
    private HealthService healthService;

    @Test
    @DisplayName("returns 200 with status UP when the database responds")
    void returnsOkWhenDatabaseIsReachable() throws Exception {
        given(healthService.check()).willReturn(HealthDto.up());

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"))
                // Guards the @JsonIgnore on HealthDto.isUp(). Jackson serialises any isX()/getX()
                // method as a property, so without it the response grows a stray "up": true field.
                // Pinning the exact shape here means a helper method can never silently become
                // part of the public API contract again.
                .andExpect(jsonPath("$.up").doesNotExist())
                .andExpect(jsonPath("$.*", hasSize(3)));
    }

    @Test
    @DisplayName("returns 503, not a cheerful 200, when the database is unreachable")
    void returns503WhenDatabaseIsDown() throws Exception {
        given(healthService.check())
                .willReturn(HealthDto.databaseDown("Connection refused"));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.detail").value("Connection refused"));
    }
}
