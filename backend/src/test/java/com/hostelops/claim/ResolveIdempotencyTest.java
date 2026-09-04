package com.hostelops.claim;

import com.hostelops.claim.dto.ResolveResultDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.support.PostgresIntegrationTest;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Approve and reject, acting on requests that are already resolved.
 *
 * <p>The audit found the concurrent case was thoroughly proven ({@code DoubleApprovalRaceTest}:
 * eight admins at once produce exactly one allocation) while the SEQUENTIAL cases - the ones a real
 * admin actually hits, by double-clicking or by reloading a stale queue - were only ever checked
 * by hand. This fills that in.
 *
 * <p>The distinction that matters throughout: acting on a request ALREADY in the state you want is
 * a success, and acting on one in a DIFFERENT terminal state is a conflict. Collapsing the two into
 * one answer would either alarm an admin whose click had the intended effect, or tell them "done"
 * about something that never happened.
 */
class ResolveIdempotencyTest extends PostgresIntegrationTest {

    @Autowired private ClaimService claimService;
    @Autowired private AdminClaimService adminClaimService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long studentId;
    private Long adminId;
    private Long secondAdminId;
    private Long bedId;

    private Long bedIdOf(int room, String label) {
        return jdbcTemplate.queryForObject(
                "SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id "
                        + "WHERE r.room_number = ? AND b.bed_label = ?", Long.class, room, label);
    }

    private Long admin(String email) {
        return userRepository.findByEmailIgnoreCase(email).map(User::getId)
                .orElseGet(() -> userRepository.save(User.admin(
                        email, passwordEncoder.encode("Id@123456"), "Idem Admin")).getId());
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");
        bedId = bedIdOf(302, "A");
        studentId = userRepository.findByEmailIgnoreCase("idem-student@hostelops.demo")
                .map(User::getId)
                .orElseGet(() -> userRepository.save(User.student(
                        "idem-student@hostelops.demo", passwordEncoder.encode("Id@123456"),
                        "Idem Student", "IDEM-001", "B.E. CSE")).getId());
        adminId = admin("idem-admin-1@hostelops.demo");
        secondAdminId = admin("idem-admin-2@hostelops.demo");
    }

    private Long newRequest() {
        return claimService.requestBed(studentId, bedId, null).claim().requestId();
    }

    @Test
    @DisplayName("approving the same request twice: 'done' both times, one allocation")
    void approveTwice() {
        Long id = newRequest();

        ResolveResultDto first = adminClaimService.approve(adminId, id);
        ResolveResultDto second = adminClaimService.approve(adminId, id);

        assertThat(first.alreadyHandled()).isFalse();
        assertThat(second.alreadyHandled()).isTrue();
        assertThat(second.status()).isEqualTo(ClaimStatus.ALLOCATED);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE status = 'ALLOCATED'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a second admin approving does not overwrite who decided, or when")
    void repeatApprovalDoesNotRewriteTheAuditTrail() {
        Long id = newRequest();
        adminClaimService.approve(adminId, id);

        var before = jdbcTemplate.queryForMap(
                "SELECT decided_at, decided_by FROM bed_claims WHERE id = ?", id);
        adminClaimService.approve(secondAdminId, id);
        var after = jdbcTemplate.queryForMap(
                "SELECT decided_at, decided_by FROM bed_claims WHERE id = ?", id);

        // The conditional update matched nothing the second time, so the record still names the
        // admin who actually made the call. Had the update been unconditional, the second admin
        // would silently have taken credit for a decision they did not make.
        assertThat(after.get("decided_by")).isEqualTo(before.get("decided_by"));
        assertThat(after.get("decided_at")).isEqualTo(before.get("decided_at"));
        assertThat(after.get("decided_by")).isEqualTo(adminId);
    }

    @Test
    @DisplayName("rejecting the same request twice: 'done' both times, reason preserved")
    void rejectTwice() {
        Long id = newRequest();

        ResolveResultDto first = adminClaimService.reject(adminId, id, "Wing reserved");
        ResolveResultDto second = adminClaimService.reject(adminId, id, "A different reason");

        assertThat(first.alreadyHandled()).isFalse();
        assertThat(second.alreadyHandled()).isTrue();

        // The stored reason is the one the student was actually given. A repeat call must not
        // rewrite history to say something else.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decision_reason FROM bed_claims WHERE id = ?", String.class, id))
                .isEqualTo("Wing reserved");
    }

    @Test
    @DisplayName("approving an already-REJECTED request is a conflict, not a success")
    void approveAfterReject() {
        Long id = newRequest();
        adminClaimService.reject(adminId, id, "No");

        assertThatThrownBy(() -> adminClaimService.approve(adminId, id))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException domain = (DomainException) e;
                    assertThat(domain.errorCode()).isEqualTo(ErrorCode.REQUEST_ALREADY_RESOLVED);
                    // The admin is told what actually happened, so they can see the other decision
                    // rather than assuming their click worked.
                    assertThat(domain.details()).containsEntry("actualStatus", "REJECTED");
                });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bed_status WHERE bed_id = ?", String.class, bedId))
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("rejecting an already-ALLOCATED request is a conflict")
    void rejectAfterApprove() {
        Long id = newRequest();
        adminClaimService.approve(adminId, id);

        assertThatThrownBy(() -> adminClaimService.reject(adminId, id, "Changed my mind"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).details())
                        .containsEntry("actualStatus", "ALLOCATED"));

        // The student keeps their bed. Rejecting an allocation would be an eviction, which is a
        // separate workflow and out of scope - so it must not happen by the back door.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bed_status WHERE bed_id = ?", String.class, bedId))
                .isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("approving a request the student already cancelled is a conflict")
    void approveAfterStudentCancelled() {
        Long id = newRequest();
        claimService.cancelOwnRequest(studentId, id);

        assertThatThrownBy(() -> adminClaimService.approve(adminId, id))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).details())
                        .containsEntry("actualStatus", "CANCELLED"));

        // Crucially the bed is NOT allocated to someone who withdrew. Without the conditional
        // update, an admin working from a stale queue would hand out a bed nobody asked for.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bed_status WHERE bed_id = ?", String.class, bedId))
                .isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("approving a request an EXPIRED sweep already closed is a conflict")
    void approveAfterExpiry() {
        Long id = newRequest();
        jdbcTemplate.update("""
                UPDATE bed_claims SET status='EXPIRED', decided_at=now(),
                       decision_reason='swept', expires_at=NULL
                 WHERE id = ?
                """, id);

        assertThatThrownBy(() -> adminClaimService.approve(adminId, id))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).details())
                        .containsEntry("actualStatus", "EXPIRED"));
    }

    @Test
    @DisplayName("resolving a request that does not exist is a 404")
    void resolvingAMissingRequest() {
        assertThatThrownBy(() -> adminClaimService.approve(adminId, 999_999L))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        assertThatThrownBy(() -> adminClaimService.reject(adminId, 999_999L, "n/a"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a rejection frees the student as well as the bed")
    void rejectionReleasesTheStudent() {
        Long id = newRequest();
        adminClaimService.reject(adminId, id, "Try another wing");

        // Their slot in the per-student index is free again, so a rejection is not a lockout.
        assertThat(claimService.requestBed(studentId, bedIdOf(303, "A"), null).created()).isTrue();
    }
}
