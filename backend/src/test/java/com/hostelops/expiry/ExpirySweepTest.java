package com.hostelops.expiry;

import com.hostelops.claim.AdminClaimService;
import com.hostelops.claim.ClaimService;
import com.hostelops.claim.dto.MyAllocationDto;
import com.hostelops.support.PostgresIntegrationTest;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-expiry: the safety net that stops one unanswered request freezing a bed forever.
 *
 * <p>The sweep is called directly rather than waited for. A test that slept for the scheduler would
 * be slow and flaky, and the thing worth proving is what expiring DOES, not that Spring's timer
 * fires.
 */
class ExpirySweepTest extends PostgresIntegrationTest {

    @Autowired private ClaimExpiryService expiryService;
    @Autowired private ClaimService claimService;
    @Autowired private AdminClaimService adminClaimService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long studentId;
    private Long adminId;
    private Long bedId;

    private Long bedIdOf(int room, String label) {
        return jdbcTemplate.queryForObject(
                "SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id "
                        + "WHERE r.room_number = ? AND b.bed_label = ?", Long.class, room, label);
    }

    private String statusOf(Long bedId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bed_status WHERE bed_id = ?", String.class, bedId);
    }

    /** Drags a request's deadline into the past, standing in for 48 hours having elapsed. */
    private void makeOverdue(Long requestId) {
        jdbcTemplate.update(
                "UPDATE bed_claims SET expires_at = now() - interval '1 minute' WHERE id = ?",
                requestId);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");
        bedId = bedIdOf(501, "A");

        studentId = userRepository.findByEmailIgnoreCase("expiry-student@hostelops.demo")
                .map(User::getId)
                .orElseGet(() -> userRepository.save(User.student(
                        "expiry-student@hostelops.demo", passwordEncoder.encode("Exp@12345"),
                        "Expiry Student", "EXP001", "B.E. CSE")).getId());

        adminId = userRepository.findByEmailIgnoreCase("expiry-admin@hostelops.demo")
                .map(User::getId)
                .orElseGet(() -> userRepository.save(User.admin(
                        "expiry-admin@hostelops.demo", passwordEncoder.encode("Exp@12345"),
                        "Expiry Admin")).getId());
    }

    @Test
    @DisplayName("an overdue request expires, and the bed becomes AVAILABLE again")
    void overdueRequestExpiresAndFreesTheBed() {
        var request = claimService.requestBed(studentId, bedId, null);
        assertThat(statusOf(bedId)).isEqualTo("PENDING");

        makeOverdue(request.claim().requestId());
        List<ExpiredClaim> expired = expiryService.sweep();

        assertThat(expired).hasSize(1);
        assertThat(expired.get(0).requestId()).isEqualTo(request.claim().requestId());
        assertThat(expired.get(0).roomNumber()).isEqualTo(501);

        // Nothing had to "unstick" the bed. The row left the index's WHERE clause, and the status
        // is derived fresh from what remains.
        assertThat(statusOf(bedId)).isEqualTo("AVAILABLE");

        // The student is released too, so a lapsed request does not bar them from the system.
        assertThat(claimService.myAllocation(studentId).state()).isEqualTo(MyAllocationDto.State.NONE);
        assertThat(claimService.requestBed(studentId, bedIdOf(502, "A"), null).created()).isTrue();
    }

    @Test
    @DisplayName("EXPIRED records when it happened and why, but names no decider")
    void expiryIsSystemDrivenAndAudited() {
        var request = claimService.requestBed(studentId, bedId, null);
        makeOverdue(request.claim().requestId());
        expiryService.sweep();

        var row = jdbcTemplate.queryForMap(
                "SELECT status, decided_at, decided_by, decision_reason, expires_at "
                        + "FROM bed_claims WHERE id = ?", request.claim().requestId());

        assertThat(row.get("status")).isEqualTo("EXPIRED");
        assertThat(row.get("decided_at")).isNotNull();
        // No human expired it, so there is nobody to name. ck_claims_decided encodes exactly this:
        // EXPIRED is the single transition that requires a decided_at and forbids a decided_by.
        assertThat(row.get("decided_by")).isNull();
        assertThat((String) row.get("decision_reason")).isNotBlank();
        // ck_claims_ttl: only a PENDING row carries a deadline.
        assertThat(row.get("expires_at")).isNull();
    }

    @Test
    @DisplayName("a request still within its TTL is left alone")
    void freshRequestSurvives() {
        var request = claimService.requestBed(studentId, bedId, null);

        assertThat(expiryService.sweep()).isEmpty();
        assertThat(statusOf(bedId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bed_claims WHERE id = ?", String.class,
                request.claim().requestId())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("an ALLOCATED bed is never expired, however old")
    void allocationsAreNeverExpired() {
        var request = claimService.requestBed(studentId, bedId, null);
        adminClaimService.approve(adminId, request.claim().requestId());

        // Nudge the clock as far as it will go. An allocation has expires_at NULL, so it cannot
        // match the sweep's predicate at all - a student does not lose their room to a timer.
        jdbcTemplate.update("UPDATE bed_claims SET created_at = now() - interval '400 days' "
                + "WHERE id = ?", request.claim().requestId());

        assertThat(expiryService.sweep()).isEmpty();
        assertThat(statusOf(bedId)).isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("a BLOCKED bed is never expired either")
    void blocksAreNeverExpired() {
        adminClaimService.blockBed(adminId, bedId, "Long refurbishment");

        assertThat(expiryService.sweep()).isEmpty();
        assertThat(statusOf(bedId)).isEqualTo("BLOCKED");
    }

    @Test
    @DisplayName("sweeping twice does not expire the same request twice")
    void sweepIsIdempotent() {
        var request = claimService.requestBed(studentId, bedId, null);
        makeOverdue(request.claim().requestId());

        assertThat(expiryService.sweep()).hasSize(1);
        assertThat(expiryService.sweep()).isEmpty();

        // One row, one decision timestamp - not overwritten by the second pass.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("an admin approving as the sweep runs -> one wins, no row is both")
    void approveVersusExpiry() throws Exception {
        var request = claimService.requestBed(studentId, bedId, null);
        Long requestId = request.claim().requestId();
        makeOverdue(requestId);

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Integer> sweeping = pool.submit(() -> {
            startLine.await(10, TimeUnit.SECONDS);
            return expiryService.sweep().size();
        });
        Future<Boolean> approving = pool.submit(() -> {
            startLine.await(10, TimeUnit.SECONDS);
            try {
                return !adminClaimService.approve(adminId, requestId).alreadyHandled();
            } catch (Exception e) {
                return false; // told the request was already resolved
            }
        });

        Thread.sleep(30);
        startLine.countDown();
        int expiredCount = sweeping.get(30, TimeUnit.SECONDS);
        boolean approvalPerformed = approving.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // Exactly one of them changed the row. The sweep is not privileged over an admin, nor the
        // reverse - both use the same "only if still PENDING" predicate, so whoever commits first
        // wins and the other quietly does nothing.
        assertThat(expiredCount + (approvalPerformed ? 1 : 0))
                .as("exactly one of sweep/approve took effect")
                .isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bed_claims WHERE id = ?", String.class, requestId))
                .isIn("EXPIRED", "ALLOCATED");
    }

    @Test
    @DisplayName("two sweeps running at once expire each request exactly once")
    void concurrentSweepsDoNotDoubleExpire() throws Exception {
        // Stands in for two application instances behind a load balancer, each running its own
        // scheduler. There is no leader election and no distributed lock; the conditional update
        // is the whole coordination mechanism.
        List<Long> requestIds = new ArrayList<>();
        int[] rooms = {503, 504, 505, 506};
        for (int i = 0; i < rooms.length; i++) {
            String email = "sweep-student-" + i + "@hostelops.demo";
            Long id = userRepository.findByEmailIgnoreCase(email)
                    .map(User::getId)
                    .orElseGet(() -> userRepository.save(User.student(
                            email, passwordEncoder.encode("Swp@12345"),
                            "Sweep Student", "SWP" + System.nanoTime(), "B.E. CSE")).getId());
            var r = claimService.requestBed(id, bedIdOf(rooms[i], "A"), null);
            requestIds.add(r.claim().requestId());
            makeOverdue(r.claim().requestId());
        }

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> sweeps = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Callable<Integer> sweep = () -> {
                startLine.await(10, TimeUnit.SECONDS);
                return expiryService.sweep().size();
            };
            sweeps.add(pool.submit(sweep));
        }
        startLine.countDown();

        int total = 0;
        for (Future<Integer> s : sweeps) {
            total += s.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        // Each request counted by exactly one sweep - four expiries in total, not eight.
        assertThat(total).isEqualTo(requestIds.size());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE status = 'EXPIRED'", Integer.class))
                .isEqualTo(requestIds.size());
    }

    @Test
    @DisplayName("the admin queue shows how long each request has waited and when it expires")
    void queueShowsPendingAge() {
        claimService.requestBed(studentId, bedId, null);
        jdbcTemplate.update("UPDATE bed_claims SET created_at = now() - interval '3 hours' "
                + "WHERE status = 'PENDING'");

        var row = adminClaimService.pendingQueue(0, 25).rows().get(0);

        assertThat(row.pendingAgeSeconds()).isGreaterThan(3 * 3600 - 60);
        assertThat(row.expiresAt()).isNotNull();
    }
}
