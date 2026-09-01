package com.hostelops.concurrency;

import com.hostelops.claim.ClaimService;
import com.hostelops.claim.dto.MyAllocationDto;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The second partial unique index: one live claim per STUDENT.
 *
 * <p>The same mechanism as the per-bed index, reused for an entirely different rule - it stops a
 * student spamming requests across many beds, and stops a student who already has an allocation
 * from taking a second one.
 *
 * <p>Crucially the rejection comes from the database, not from an {@code if} in the service. There
 * is no "does this student already have a claim?" check anywhere in the request path, because such
 * a check could be passed by two requests at once and would then let both inserts through.
 */
class OneClaimPerStudentTest extends PostgresIntegrationTest {

    @Autowired private ClaimService claimService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long studentId;
    private Long bedOne;
    private Long bedTwo;

    private Long bedIdOf(int roomNumber, String label) {
        return jdbcTemplate.queryForObject(
                "SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id "
                        + "WHERE r.room_number = ? AND b.bed_label = ?", Long.class, roomNumber, label);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");
        bedOne = bedIdOf(201, "A");
        bedTwo = bedIdOf(202, "A");

        studentId = userRepository.findByEmailIgnoreCase("one-claim@hostelops.demo")
                .map(User::getId)
                .orElseGet(() -> userRepository.save(User.student(
                        "one-claim@hostelops.demo", passwordEncoder.encode("Once@12345"),
                        "Single Claim Student", "ONE001", "B.E. CSE")).getId());
    }

    @Test
    @DisplayName("a student with a PENDING request cannot request a second bed")
    void pendingBlocksASecondRequest() {
        claimService.requestBed(studentId, bedOne, null);

        assertThatThrownBy(() -> claimService.requestBed(studentId, bedTwo, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.STUDENT_ALREADY_HAS_CLAIM);

        assertThat(liveClaimsFor(studentId)).isEqualTo(1);
        // The second bed was never touched.
        assertThat(statusOf(bedTwo)).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("a student with an ALLOCATED bed cannot request another")
    void allocationBlocksAFurtherRequest() {
        claimService.requestBed(studentId, bedOne, null);
        // Stand in for Phase 5's approve, which does not exist yet.
        jdbcTemplate.update("""
                UPDATE bed_claims SET status='ALLOCATED', decided_at=now(),
                       decided_by=(SELECT id FROM users WHERE role='ADMIN' LIMIT 1),
                       expires_at=NULL
                 WHERE student_id=? AND status='PENDING'
                """, studentId);

        assertThatThrownBy(() -> claimService.requestBed(studentId, bedTwo, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.STUDENT_ALREADY_HAS_CLAIM);
    }

    @Test
    @DisplayName("cancelling frees the student AND the bed, and both can be reused")
    void cancellingReleasesBoth() {
        var first = claimService.requestBed(studentId, bedOne, null);
        assertThat(statusOf(bedOne)).isEqualTo("PENDING");

        claimService.cancelOwnRequest(studentId, first.claim().requestId());

        // The bed goes back to AVAILABLE with nothing to "unstick" - the cancelled row simply
        // leaves the index's WHERE clause, and status is derived fresh from what remains.
        assertThat(statusOf(bedOne)).isEqualTo("AVAILABLE");
        assertThat(liveClaimsFor(studentId)).isZero();
        assertThat(claimService.myAllocation(studentId).state()).isEqualTo(MyAllocationDto.State.NONE);

        // Both are immediately reusable, and the cancelled row stays as history.
        claimService.requestBed(studentId, bedOne, null);
        assertThat(statusOf(bedOne)).isEqualTo("PENDING");
        assertThat(totalClaims()).isEqualTo(2);
    }

    @Test
    @DisplayName("one student firing 8 simultaneous requests at 8 different beds gets exactly one")
    void spammingManyBedsAtOnceStillYieldsOneClaim() throws Exception {
        // The realistic abuse case, and the one an if-check cannot stop: all eight requests read
        // "this student has no claim" before any of them commits.
        List<Long> beds = new ArrayList<>();
        for (int room = 203; room < 211; room++) {
            beds.add(bedIdOf(room, "A"));
        }

        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(beds.size());
        ExecutorService pool = Executors.newFixedThreadPool(beds.size());

        List<Future<Boolean>> futures = new ArrayList<>();
        for (Long bedId : beds) {
            Callable<Boolean> attempt = () -> {
                ready.countDown();
                startLine.await(10, TimeUnit.SECONDS);
                try {
                    claimService.requestBed(studentId, bedId, null);
                    return true;
                } catch (DomainException e) {
                    return false;
                }
            };
            futures.add(pool.submit(attempt));
        }

        ready.await(10, TimeUnit.SECONDS);
        startLine.countDown();

        int succeeded = 0;
        for (Future<Boolean> f : futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdownNow();

        assertThat(succeeded).as("requests accepted").isEqualTo(1);
        assertThat(liveClaimsFor(studentId)).as("live claims in the database").isEqualTo(1);
    }

    @Test
    @DisplayName("the same Idempotency-Key twice returns the original request, not a second one")
    void repeatedIdempotencyKeyReplays() {
        UUID key = UUID.randomUUID();

        var first = claimService.requestBed(studentId, bedOne, key);
        var second = claimService.requestBed(studentId, bedOne, key);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).as("a replay, not a new row").isFalse();
        assertThat(second.claim().requestId()).isEqualTo(first.claim().requestId());
        assertThat(totalClaims()).isEqualTo(1);
    }

    private Integer liveClaimsFor(Long studentId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE student_id = ? AND status IN "
                        + "('PENDING','ALLOCATED')", Integer.class, studentId);
    }

    private String statusOf(Long bedId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bed_status WHERE bed_id = ?", String.class, bedId);
    }

    private Integer totalClaims() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM bed_claims", Integer.class);
    }
}
