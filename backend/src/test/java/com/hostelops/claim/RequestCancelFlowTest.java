package com.hostelops.claim;

import com.hostelops.claim.dto.CancelResultDto;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The paths through request and cancel that are NOT the happy path.
 *
 * <p>The audit found these were the real hole. Earlier phases proved the concurrency rules
 * thoroughly and the happy path incidentally, but every authorization and error branch of cancel
 * had only ever been checked by hand with curl - which proves it worked once, on one machine, and
 * guards nothing against a later refactor.
 */
class RequestCancelFlowTest extends PostgresIntegrationTest {

    @Autowired private ClaimService claimService;
    @Autowired private AdminClaimService adminClaimService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long owner;
    private Long stranger;
    private Long adminId;
    private Long bedId;

    private Long bedIdOf(int room, String label) {
        return jdbcTemplate.queryForObject(
                "SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id "
                        + "WHERE r.room_number = ? AND b.bed_label = ?", Long.class, room, label);
    }

    private Long student(String email, String code) {
        return userRepository.findByEmailIgnoreCase(email).map(User::getId)
                .orElseGet(() -> userRepository.save(User.student(
                        email, passwordEncoder.encode("Fl@123456"),
                        "Flow Student", code, "B.E. CSE")).getId());
    }

    private static ErrorCode errorFrom(Runnable action) {
        try {
            action.run();
            return null;
        } catch (DomainException e) {
            return e.errorCode();
        }
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");
        bedId = bedIdOf(301, "A");
        owner = student("flow-owner@hostelops.demo", "FLOW-001");
        stranger = student("flow-stranger@hostelops.demo", "FLOW-002");
        adminId = userRepository.findByEmailIgnoreCase("flow-admin@hostelops.demo").map(User::getId)
                .orElseGet(() -> userRepository.save(User.admin(
                        "flow-admin@hostelops.demo", passwordEncoder.encode("Fl@123456"),
                        "Flow Admin")).getId());
    }

    @Test
    @DisplayName("requesting a bed that does not exist is a 404, not a crash")
    void requestingAMissingBed() {
        assertThat(errorFrom(() -> claimService.requestBed(owner, 999_999L, null)))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("a student cannot cancel somebody else's request")
    void cannotCancelAnotherStudentsRequest() {
        var request = claimService.requestBed(owner, bedId, null);

        // The ownership check is in the WHERE clause of the update AND asserted explicitly, so a
        // stranger cannot free a bed out from under its holder even knowing the request id.
        assertThat(errorFrom(() ->
                claimService.cancelOwnRequest(stranger, request.claim().requestId())))
                .isEqualTo(ErrorCode.FORBIDDEN);

        // And nothing changed.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bed_claims WHERE id = ?", String.class,
                request.claim().requestId())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("cancelling a request that does not exist is a 404")
    void cancellingAMissingRequest() {
        assertThat(errorFrom(() -> claimService.cancelOwnRequest(owner, 999_999L)))
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("cancelling twice succeeds both times, the second reporting alreadyHandled")
    void cancellingTwiceIsIdempotent() {
        var request = claimService.requestBed(owner, bedId, null);
        Long id = request.claim().requestId();

        CancelResultDto first = claimService.cancelOwnRequest(owner, id);
        CancelResultDto second = claimService.cancelOwnRequest(owner, id);

        assertThat(first.alreadyHandled()).isFalse();
        // Not an error: the caller wanted the request gone and it is gone. A double-click, a retry
        // after a lost response, and two tabs all get the same truthful answer.
        assertThat(second.alreadyHandled()).isTrue();
        assertThat(second.status()).isEqualTo(ClaimStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancelling an ALLOCATED request is refused, naming the actual status")
    void cannotCancelAnApprovedRequest() {
        var request = claimService.requestBed(owner, bedId, null);
        adminClaimService.approve(adminId, request.claim().requestId());

        // Giving up an allocated bed is not the same action as withdrawing a request, and quietly
        // treating it as one would let a student release a confirmed room through the cancel path.
        assertThatThrownBy(() ->
                claimService.cancelOwnRequest(owner, request.claim().requestId()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException domain = (DomainException) e;
                    assertThat(domain.errorCode()).isEqualTo(ErrorCode.REQUEST_ALREADY_RESOLVED);
                    assertThat(domain.details()).containsEntry("actualStatus", "ALLOCATED");
                });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bed_status WHERE bed_id = ?", String.class, bedId))
                .isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("cancelling a request an admin already rejected reports the real outcome")
    void cancellingARejectedRequest() {
        var request = claimService.requestBed(owner, bedId, null);
        adminClaimService.reject(adminId, request.claim().requestId(), "Not this wing");

        assertThatThrownBy(() ->
                claimService.cancelOwnRequest(owner, request.claim().requestId()))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).details())
                        .containsEntry("actualStatus", "REJECTED"));
    }

    @Test
    @DisplayName("different Idempotency-Keys for the same bed are two real attempts, not a replay")
    void differentKeysAreNotReplays() {
        claimService.requestBed(owner, bedId, null);

        // A DIFFERENT key from a DIFFERENT student is a genuine second attempt and must be judged
        // on the merits - here rejected by the per-bed index, not silently replayed as a success.
        assertThat(errorFrom(() -> claimService.requestBed(stranger, bedId, UUID.randomUUID())))
                .isEqualTo(ErrorCode.BED_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("a replayed key returns the original even after the bed changed hands")
    void replayIsStableOverTime() {
        UUID key = UUID.randomUUID();
        var first = claimService.requestBed(owner, bedId, key);
        adminClaimService.approve(adminId, first.claim().requestId());

        // Replaying the key must return that same request - now ALLOCATED - rather than attempting
        // a fresh insert that would collide, or inventing a second row.
        var replay = claimService.requestBed(owner, bedId, key);
        assertThat(replay.created()).isFalse();
        assertThat(replay.claim().requestId()).isEqualTo(first.claim().requestId());
        assertThat(replay.claim().status()).isEqualTo(ClaimStatus.ALLOCATED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("a request carries a deadline; an allocation does not")
    void deadlineOnlyWhilePending() {
        var request = claimService.requestBed(owner, bedId, null);
        assertThat(request.claim().expiresAt()).isNotNull();

        adminClaimService.approve(adminId, request.claim().requestId());

        // ck_claims_ttl in the schema, observable through the API: only a PENDING row has an
        // expires_at, which is also why an allocation can never be swept away by the expiry job.
        assertThat(claimService.myAllocation(owner).claim().expiresAt()).isNull();
    }
}
