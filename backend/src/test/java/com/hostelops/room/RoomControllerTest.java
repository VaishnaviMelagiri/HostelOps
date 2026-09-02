package com.hostelops.room;

import com.hostelops.config.CorsConfig;
import com.hostelops.config.SecurityConfig;
import com.hostelops.room.dto.BedStateDto;
import com.hostelops.room.dto.BedStatus;
import com.hostelops.room.dto.CellDto;
import com.hostelops.room.dto.FloorRoomsDto;
import com.hostelops.room.dto.GridDto;
import com.hostelops.room.dto.RoomCellDto;
import com.hostelops.room.dto.RoomStatus;
import com.hostelops.security.AppUserDetailsService;
import com.hostelops.security.AppUserPrincipal;
import com.hostelops.security.JwtService;
import com.hostelops.security.TokenRevocationService;
import com.hostelops.user.Role;
import com.hostelops.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The floor map's HTTP contract and who may read it.
 */
@WebMvcTest(controllers = RoomController.class)
@Import({SecurityConfig.class, CorsConfig.class})
class RoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private RoomQueryService roomQueryService;

    // Needed only so JwtAuthFilter can be constructed; the tests supply a principal directly.
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

    private static FloorRoomsDto sampleFloor() {
        return new FloorRoomsDto("B", "FF", 1, new GridDto(6, 2), List.of(
                new RoomCellDto(1L, 113, "Double", "Attached", 2,
                        new CellDto(1, 1), RoomStatus.PARTIAL,
                        List.of(new BedStateDto(11L, "A", BedStatus.PENDING),
                                new BedStateDto(12L, "B", BedStatus.AVAILABLE)))));
    }

    @Test
    @DisplayName("browsing requires a signed-in user")
    void anonymousCannotBrowse() throws Exception {
        mockMvc.perform(get("/api/wings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("all three roles may read a floor - the payload is identical for each")
    void everyRoleCanBrowse() throws Exception {
        given(roomQueryService.getFloor(anyString(), anyString())).willReturn(sampleFloor());

        String previous = null;
        for (Role role : Role.values()) {
            String body = mockMvc.perform(
                            get("/api/wings/B/floors/FF/rooms").with(user(principal(role))))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            if (previous != null) {
                // Byte-identical across roles. There is no per-role redaction to get wrong,
                // because there is nothing sensitive in the payload to redact.
                org.assertj.core.api.Assertions.assertThat(body)
                        .as("%s sees the same floor payload as the previous role", role)
                        .isEqualTo(previous);
            }
            previous = body;
        }
    }

    @Test
    @DisplayName("the floor payload carries bed statuses but never a student identity")
    void payloadExposesNoIdentity() throws Exception {
        given(roomQueryService.getFloor(anyString(), anyString())).willReturn(sampleFloor());

        mockMvc.perform(get("/api/wings/B/floors/FF/rooms").with(user(principal(Role.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grid.columns").value(6))
                .andExpect(jsonPath("$.rooms[0].roomNumber").value(113))
                .andExpect(jsonPath("$.rooms[0].cell.col").value(1))
                .andExpect(jsonPath("$.rooms[0].beds[0].status").value("PENDING"))
                // Even for an ADMIN. Identities live on the pending-queue endpoint (Phase 5) and
                // nowhere else, so there is exactly one place to guard rather than two.
                .andExpect(jsonPath("$.rooms[0].beds[0].studentId").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("fullName"))));
    }

    @Test
    @DisplayName("an unmatched URL returns 404, not 500")
    void unknownEndpointIs404() throws Exception {
        // Regression guard. GlobalExceptionHandler has a catch-all on Exception, which used to
        // swallow Spring's own "no handler found" exception and report a mistyped URL as
        // 500 INTERNAL_ERROR - telling the client the server was broken when nothing was.
        mockMvc.perform(get("/api/definitely-not-an-endpoint").with(user(principal(Role.ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("the right URL with the wrong HTTP method returns 405, not 500")
    void wrongMethodIs405() throws Exception {
        mockMvc.perform(post("/api/wings").with(user(principal(Role.ADMIN))))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
