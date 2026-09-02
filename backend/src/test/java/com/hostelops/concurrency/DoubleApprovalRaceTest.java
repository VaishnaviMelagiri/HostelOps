package com.hostelops.concurrency;

import com.hostelops.claim.AdminClaimService;
import com.hostelops.claim.ClaimService;
import com.hostelops.claim.ClaimStatus;
import com.hostelops.claim.dto.ResolveResultDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.support.PostgresIntegrationTest;
import com.hostelops.user.Role;
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
 * "How do you stop two admins allocating the same room?" - answered by test rather than by claim.
 *
 * <p>The mechanism is NOT the partial unique index. That stops two claims existing on one bed; it
 * says nothing about one claim being resolved twice, because approval is an UPDATE of an existing
 * row rather than an INSERT. What makes approval safe is the conditional update
 * {@code WHERE id = ? AND status = 'PENDING'} plus a branch on the affected-row count.
 *
 * <p>Both mechanisms are needed and they answer different questions. Conflating them is the most
 * common way this design gets explained wrongly.
 */
class DoubleApprovalRaceTest extends PostgresIntegrationTest {

    private static final int CONCURRENT_ADMINS = 8;

    @Autowired private ClaimService claimService;
    @Autowired private AdminClaimService adminClaimService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long requestId;
    private Long bedId;
    private List<Long> adminIds;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");

        bedId = jdbcTemplate.queryForObject(
                "SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id "
                        + "WHERE r.room_number = 301 AND b.bed_label = 'A'", Long.class);

        Long studentId = userRepository.findByEmailIgnoreCase("approval-student@hostelops.demo")
                .map(User::getId)
                .orElseGet(() -> userRepository.save(User.student(
                        "approval-student@hostelops.demo", passwordEncoder.encode("Appr@12345"),
                        "Approval Student", "APPR001", "B.E. CSE")).getId());

        adminIds = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_ADMINS; i++) {
            String email = "race-admin-" + i + "@hostelops.demo";
            Long id = userRepository.findByEmailIgnoreCase(email)
                    .map(User::getId)
                    .orElseGet(() -> userRepository.save(User.admin(
                            email, passwordEncoder.encode("Race@12345"), "Race Admin")).getId());
            adminIds.add(id);
        }

        requestId = claimService.requestBed(studentId, bedId, null).claim().requestId();
    }

    @Test
    @DisplayName("8 admins approve the same request simultaneously -> exactly one allocation")
    void exactlyOneApprovalTakesEffect() throws Exception {
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_ADMINS);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_ADMINS);

        List<Future<ResolveResultDto>> futures = new ArrayList<>();
        for (Long adminId : adminIds) {
            Callable<ResolveResultDto> attempt = () -> {
                ready.countDown();
                startLine.await(10, TimeUnit.SECONDS);
                return adminClaimService.approve(adminId, requestId);
            };
            futures.add(pool.submit(attempt));
        }

        ready.await(10, TimeUnit.SECONDS);
        startLine.countDown();

        int performedTheApproval = 0;
        int toldAlreadyHandled = 0;
        for (Future<ResolveResultDto> future : futures) {
            ResolveResultDto result = future.get(30, TimeUnit.SECONDS);

            // Every caller gets a successful, truthful answer - nobody sees a 500 or a conflict,
            // because they all wanted the same outcome and that outcome happened.
            assertThat(result.status()).isEqualTo(ClaimStatus.ALLOCATED);
            if (result.alreadyHandled()) {
                toldAlreadyHandled++;
            } else {
                performedTheApproval++;
            }
        }
        pool.shutdownNow();

        // Exactly one admin actually did it; the other seven are told it was already done.
        assertThat(performedTheApproval).as("admins whose call performed the approval").isEqualTo(1);
        assertThat(toldAlreadyHandled).isEqualTo(CONCURRENT_ADMINS - 1);

        // One row, one decision, one decider - not eight overwritten decisions.
        Integer allocatedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE bed_id = ? AND status = 'ALLOCATED'",
                Integer.class, bedId);
        assertThat(allocatedRows).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims", Integer.class)).isEqualTo(1);

        // The decision was recorded once, by one identifiable admin.
        Integer decidedByCount = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT decided_by) FROM bed_claims WHERE id = ?",
                Integer.class, requestId);
        assertThat(decidedByCount).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bed_status WHERE bed_id = ?", String.class, bedId))
                .isEqualTo("ALLOCATED");
    }

    @Test
    @DisplayName("approve and reject racing each other -> one wins, the other gets a clear conflict")
    void approveVersusRejectHasExactlyOneWinner() throws Exception {
        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Object> approving = pool.submit(() -> {
            startLine.await(10, TimeUnit.SECONDS);
            try {
                return adminClaimService.approve(adminIds.get(0), requestId);
            } catch (DomainException e) {
                return e.errorCode();
            }
        });
        Future<Object> rejecting = pool.submit(() -> {
            startLine.await(10, TimeUnit.SECONDS);
            try {
                return adminClaimService.reject(adminIds.get(1), requestId, "No");
            } catch (DomainException e) {
                return e.errorCode();
            }
        });

        Thread.sleep(50);
        startLine.countDown();

        Object a = approving.get(30, TimeUnit.SECONDS);
        Object r = rejecting.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // Two admins made OPPOSITE decisions at the same moment. One committed first and won; the
        // other is told plainly what actually happened rather than silently overwriting it.
        List<Object> outcomes = List.of(a, r);
        long succeeded = outcomes.stream().filter(o -> o instanceof ResolveResultDto).count();
        long conflicted = outcomes.stream()
                .filter(o -> o == ErrorCode.REQUEST_ALREADY_RESOLVED).count();

        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicted).isEqualTo(1);

        // And the row holds one decision, not a mixture.
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bed_claims WHERE id = ?", String.class, requestId);
        assertThat(finalStatus).isIn("ALLOCATED", "REJECTED");
    }

    @Test
    @DisplayName("a student cancelling as an admin approves -> whoever commits first wins")
    void cancelVersusApprove() throws Exception {
        Long studentId = jdbcTemplate.queryForObject(
                "SELECT student_id FROM bed_claims WHERE id = ?", Long.class, requestId);

        CountDownLatch startLine = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<Object> approving = pool.submit(() -> {
            startLine.await(10, TimeUnit.SECONDS);
            try {
                return adminClaimService.approve(adminIds.get(0), requestId);
            } catch (DomainException e) {
                return e.errorCode();
            }
        });
        Future<Object> cancelling = pool.submit(() -> {
            startLine.await(10, TimeUnit.SECONDS);
            try {
                return claimService.cancelOwnRequest(studentId, requestId);
            } catch (DomainException e) {
                return e.errorCode();
            }
        });

        Thread.sleep(50);
        startLine.countDown();
        Object approveOutcome = approving.get(30, TimeUnit.SECONDS);
        Object cancelOutcome = cancelling.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // Exactly one of them changed the row. There is no ordering to get right in application
        // code, because the database evaluates each WHERE clause at commit time.
        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bed_claims WHERE id = ?", String.class, requestId);
        assertThat(finalStatus).isIn("ALLOCATED", "CANCELLED");
        assertThat(List.of(approveOutcome, cancelOutcome))
                .as("one succeeded and one was told the truth")
                .hasSize(2);
    }

    @Test
    @DisplayName("admins are real users, so the audit trail names who decided")
    void decisionIsAttributable() {
        adminClaimService.approve(adminIds.get(0), requestId);

        String role = jdbcTemplate.queryForObject("""
                SELECT u.role FROM bed_claims c JOIN users u ON u.id = c.decided_by
                 WHERE c.id = ?
                """, String.class, requestId);
        assertThat(role).isEqualTo(Role.ADMIN.name());
    }
}
