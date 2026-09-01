package com.hostelops.concurrency;

import com.hostelops.claim.BedClaimRepository;
import com.hostelops.claim.ClaimService;
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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE test this project exists to pass.
 *
 * <p>Many students click "Request this bed" on the same bed at the same instant. Exactly one row
 * may result. Not "usually one" - exactly one, every time, with no application-level lock, no
 * retry loop and no SERIALIZABLE isolation. The guarantee comes from
 * {@code uq_claim_live_per_bed}, a partial unique index evaluated by Postgres at the moment of
 * writing.
 *
 * <p>Note the students are all DIFFERENT people. Using one student repeatedly would make the test
 * pass for the wrong reason: the per-student index would reject the duplicates and the per-bed
 * index would never be exercised at all. That distinction is the difference between a test that
 * proves something and a test that looks like it does.
 */
class SameBedRaceTest extends PostgresIntegrationTest {

    private static final int CONCURRENT_STUDENTS = 12;

    @Autowired private ClaimService claimService;
    @Autowired private BedClaimRepository claimRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long contestedBedId;
    private List<Long> studentIds;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");

        contestedBedId = jdbcTemplate.queryForObject(
                "SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id "
                        + "WHERE r.room_number = 101 AND b.bed_label = 'A'", Long.class);

        studentIds = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_STUDENTS; i++) {
            String email = "race-student-" + i + "@hostelops.demo";
            Long id = userRepository.findByEmailIgnoreCase(email)
                    .map(User::getId)
                    .orElseGet(() -> userRepository.save(User.student(
                            email, passwordEncoder.encode("Race@12345"),
                            "Race Student", "RACE" + System.nanoTime(), "B.E. CSE")).getId());
            studentIds.add(id);
        }
    }

    @Test
    @DisplayName("12 students request the same bed simultaneously -> exactly one PENDING row")
    void exactlyOneStudentWinsTheBed() throws Exception {
        // A latch held at 1 keeps every thread parked on the start line. Releasing it lets them all
        // run at once, which is what makes this a genuine race rather than 12 sequential calls that
        // would never collide.
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(CONCURRENT_STUDENTS);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_STUDENTS);
        List<Callable<Outcome>> attempts = new ArrayList<>();

        for (Long studentId : studentIds) {
            attempts.add(() -> {
                ready.countDown();
                startLine.await(10, TimeUnit.SECONDS);
                try {
                    claimService.requestBed(studentId, contestedBedId, null);
                    return Outcome.WON;
                } catch (DomainException e) {
                    return e.errorCode() == ErrorCode.BED_NOT_AVAILABLE
                            ? Outcome.LOST_BED_TAKEN
                            : Outcome.LOST_OTHER;
                }
            });
        }

        List<Future<Outcome>> futures = new ArrayList<>();
        for (Callable<Outcome> attempt : attempts) {
            futures.add(pool.submit(attempt));
        }

        ready.await(10, TimeUnit.SECONDS);
        startLine.countDown();          // go

        AtomicInteger won = new AtomicInteger();
        AtomicInteger lostToBedTaken = new AtomicInteger();
        AtomicInteger lostToSomethingElse = new AtomicInteger();
        for (Future<Outcome> future : futures) {
            switch (future.get(30, TimeUnit.SECONDS)) {
                case WON -> won.incrementAndGet();
                case LOST_BED_TAKEN -> lostToBedTaken.incrementAndGet();
                case LOST_OTHER -> lostToSomethingElse.incrementAndGet();
            }
        }
        pool.shutdownNow();

        // 1. Exactly one caller was told it succeeded.
        assertThat(won.get()).as("callers that got a successful response").isEqualTo(1);

        // 2. Every other caller got a clear, correct reason - not a 500, not a timeout.
        assertThat(lostToBedTaken.get()).isEqualTo(CONCURRENT_STUDENTS - 1);
        assertThat(lostToSomethingElse.get()).isZero();

        // 3. And, independently of what the API said, the database holds exactly one live row.
        //    This is the assertion that actually matters: even if the service layer were buggy,
        //    a second row physically cannot exist.
        Integer liveRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE bed_id = ? AND status IN "
                        + "('PENDING','ALLOCATED','BLOCKED')", Integer.class, contestedBedId);
        assertThat(liveRows).as("live claims on the contested bed").isEqualTo(1);

        assertThat(claimRepository.count()).as("total rows written").isEqualTo(1);

        // 4. The derived view agrees - the bed reads PENDING, not AVAILABLE and not double-booked.
        String derived = jdbcTemplate.queryForObject(
                "SELECT status FROM bed_status WHERE bed_id = ?", String.class, contestedBedId);
        assertThat(derived).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the losers are free to request a different bed straight away")
    void losingDoesNotLockAStudentOut() {
        // A rejected request must leave no trace: the student's slot in the per-student index has
        // to be free, or a lost race would silently bar them from the whole system.
        Long otherBedId = jdbcTemplate.queryForObject(
                "SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id "
                        + "WHERE r.room_number = 102 AND b.bed_label = 'A'", Long.class);

        claimService.requestBed(studentIds.get(0), contestedBedId, null);

        // Student 1 loses the contested bed...
        assertThat(catchDomainError(() -> claimService.requestBed(studentIds.get(1), contestedBedId, null)))
                .isEqualTo(ErrorCode.BED_NOT_AVAILABLE);

        // ...and immediately succeeds elsewhere.
        var claim = claimService.requestBed(studentIds.get(1), otherBedId, null);
        assertThat(claim.created()).isTrue();
    }

    private static ErrorCode catchDomainError(Runnable action) {
        try {
            action.run();
            return null;
        } catch (DomainException e) {
            return e.errorCode();
        }
    }

    private enum Outcome { WON, LOST_BED_TAKEN, LOST_OTHER }
}
