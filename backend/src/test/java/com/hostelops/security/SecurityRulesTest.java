package com.hostelops.security;

import com.hostelops.config.CorsConfig;
import com.hostelops.config.SecurityConfig;
import com.hostelops.user.Role;
import com.hostelops.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real Spring Security filter chain: {@link SecurityConfig} is imported rather than
 * stubbed, so these tests fail if the actual rules are wrong.
 *
 * <p>What is being proved here is the claim made all through Phase 0 - that a GUEST attempting a
 * student action gets a clean 403 rather than reaching a service - and that it holds because of the
 * permission model, not because of a role comparison someone remembered to write.
 *
 * <p>Authentication itself is stubbed out ({@link JwtAuthFilter}'s collaborators are mocks) and the
 * test presents an already-authenticated principal. That keeps the subject narrow: this is about
 * <em>authorization</em>. Token validation has its own coverage in {@link JwtServiceTest}.
 */
@WebMvcTest(controllers = TestProtectedController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class SecurityRulesTest {

    @Autowired
    private MockMvc mockMvc;

    // JwtAuthFilter is a @Component implementing Filter, so @WebMvcTest instantiates it and its
    // collaborators must be present. They are never actually used: each test supplies a principal
    // directly instead of a token.
    @MockitoBean private JwtService jwtService;
    @MockitoBean private TokenRevocationService tokenRevocationService;
    @MockitoBean private AppUserDetailsService appUserDetailsService;

    private static AppUserPrincipal principal(Role role) {
        User user = switch (role) {
            case STUDENT -> User.student("s@hostelops.demo", "hash", "Demo Student",
                    "1MS22CS001", "B.E. Computer Science");
            case ADMIN -> User.admin("a@hostelops.demo", "hash", "Demo Admin");
            case GUEST -> User.guest("g@hostelops.demo", "hash", "Guest Reviewer");
        };
        return new AppUserPrincipal(user);
    }

    @Test
    @DisplayName("no credentials -> 401 in the standard error envelope, not an HTML error page")
    void anonymousRequestIsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/test/room-read"))
                .andExpect(status().isUnauthorized())
                // The envelope matters as much as the status: the frontend parses every response as
                // JSON, so an HTML error page here surfaces as a confusing parse error instead of
                // "your session expired".
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("all three roles can browse the map")
    void everyRoleCanReadRooms() throws Exception {
        for (Role role : Role.values()) {
            mockMvc.perform(get("/api/test/room-read").with(user(principal(role))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("GUEST requesting a bed -> 403 FORBIDDEN, before any service runs")
    void guestCannotRequestABed() throws Exception {
        mockMvc.perform(post("/api/test/request-create").with(user(principal(Role.GUEST))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("GUEST cannot reach any admin action")
    void guestCannotAdminister() throws Exception {
        mockMvc.perform(post("/api/test/approve").with(user(principal(Role.GUEST))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("STUDENT can request a bed but cannot approve one")
    void studentCanRequestButNotApprove() throws Exception {
        mockMvc.perform(post("/api/test/request-create").with(user(principal(Role.STUDENT))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/test/approve").with(user(principal(Role.STUDENT))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADMIN can approve but cannot request a bed or read an allocation")
    void adminIsNotASupersetOfStudent() throws Exception {
        mockMvc.perform(post("/api/test/approve").with(user(principal(Role.ADMIN))))
                .andExpect(status().isOk());

        // Proves the permission model is not hierarchical. Had authorization been written as
        // `role == ADMIN || role == STUDENT`, both of these would wrongly succeed.
        mockMvc.perform(post("/api/test/request-create").with(user(principal(Role.ADMIN))))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/test/my-allocation").with(user(principal(Role.ADMIN))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("only STUDENT may read an own-allocation endpoint")
    void onlyStudentsHaveAnAllocation() throws Exception {
        mockMvc.perform(get("/api/test/my-allocation").with(user(principal(Role.STUDENT))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/test/my-allocation").with(user(principal(Role.GUEST))))
                .andExpect(status().isForbidden());
    }
}
