package com.hostelops.auth;

import com.hostelops.support.PostgresIntegrationTest;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real token, through the real filter chain, against a real database.
 *
 * <p><strong>Why this is new rather than duplication.</strong> Auth already had good coverage, but
 * each existing test stops short of the whole path:
 * <ul>
 *   <li>{@code AuthServiceTest} stubs the repository - it proves the sign-in RULES, not that a
 *       token issued by the service is later accepted.</li>
 *   <li>{@code JwtServiceTest} signs and parses tokens in isolation - no filter, no database.</li>
 *   <li>{@code SecurityRulesTest} hands MockMvc a fabricated principal, bypassing
 *       {@code JwtAuthFilter} entirely.</li>
 * </ul>
 * So nothing exercised JwtAuthFilter itself: extract header, verify signature, check the revocation
 * list, load the user, authenticate. Two behaviours the comments in that class claim were, until
 * now, asserted nowhere - that logout stops a live token, and that deactivating an account takes
 * effect immediately rather than when the token happens to expire.
 */
@AutoConfigureMockMvc
class AuthTokenLifecycleTest extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM revoked_tokens");
        jdbcTemplate.update("UPDATE users SET active = TRUE");
    }

    /** Signs in over HTTP and returns the token the server actually issued. */
    private String signIn(String email, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(body, "$.accessToken");
    }

    @Test
    @DisplayName("a token issued by login is accepted by the filter on a later request")
    void issuedTokenIsAccepted() throws Exception {
        String token = signIn("student@hostelops.demo", "Student@123");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("student@hostelops.demo"))
                .andExpect(jsonPath("$.role").value("STUDENT"))
                // Authorities are recomputed from the database on every request, not read from the
                // token, so this list reflects RolePermissions rather than whatever was signed.
                .andExpect(jsonPath("$.permissions").isArray());
    }

    @Test
    @DisplayName("logout stops a token that is still perfectly valid and unexpired")
    void logoutRevokesALiveToken() throws Exception {
        String token = signIn("student@hostelops.demo", "Student@123");

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // The signature is still good and the expiry is still minutes away. It is refused purely
        // because its jti is on the denylist - which is the entire point of keeping one, and was
        // previously only checked by a mock verifying that revoke() had been called.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM revoked_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("deactivating an account refuses its existing token straight away")
    void deactivationTakesEffectImmediately() throws Exception {
        String token = signIn("student@hostelops.demo", "Student@123");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        jdbcTemplate.update("UPDATE users SET active = FALSE WHERE email = ?",
                "student@hostelops.demo");

        // This is what the per-request database load buys. A purely self-contained token would
        // keep working for the rest of its 15 minutes, because nothing in it says "still employed".
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("revoking one token leaves another session signed in")
    void revocationIsPerToken() throws Exception {
        String first = signIn("student@hostelops.demo", "Student@123");
        String second = signIn("student@hostelops.demo", "Student@123");

        mockMvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + first))
                .andExpect(status().isNoContent());

        // Signing out on a phone must not sign you out on a laptop. Each token carries its own
        // jti, so the denylist is per-session rather than per-user.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + first))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("all three demo roles sign in through the same endpoint and get their own permissions")
    void everyRoleUsesTheSamePath() throws Exception {
        record Case(String email, String password, String role, int permissions) {}
        var cases = new Case[]{
                new Case("student@hostelops.demo", "Student@123", "STUDENT", 4),
                new Case("admin@hostelops.demo", "Admin@123", "ADMIN", 7),
                new Case("guest@hostelops.demo", "Guest@123", "GUEST", 1),
        };

        for (Case c : cases) {
            String token = signIn(c.email(), c.password());
            mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value(c.role()))
                    .andExpect(jsonPath("$.permissions.length()").value(c.permissions()));
        }
    }

    @Test
    @DisplayName("a token for a deleted user is refused rather than crashing")
    void tokenForAMissingUserIsRefused() throws Exception {
        User temp = userRepository.save(User.student(
                "temp-user@hostelops.demo", new org.springframework.security.crypto.bcrypt
                .BCryptPasswordEncoder().encode("Temp@12345"),
                "Temporary", "TMP-999", "B.E. CSE"));
        String token = signIn("temp-user@hostelops.demo", "Temp@12345");

        userRepository.deleteById(temp.getId());

        // The filter loads the user and finds nothing. It must answer 401, not 500 - a stale token
        // is an authentication problem, not a server fault.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
