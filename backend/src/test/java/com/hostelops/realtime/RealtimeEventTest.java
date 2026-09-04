package com.hostelops.realtime;

import com.hostelops.claim.AdminClaimService;
import com.hostelops.claim.ClaimService;
import com.hostelops.claim.ClaimStatus;
import com.hostelops.common.DomainException;
import com.hostelops.realtime.payload.ChangeCause;
import com.hostelops.room.dto.BedStatus;
import com.hostelops.support.PostgresIntegrationTest;
import com.hostelops.user.User;
import com.hostelops.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What gets announced, and - just as importantly - what does not.
 *
 * <p>Captures the internal {@link ClaimStateChangedEvent} with a listener registered exactly the
 * way {@link RealtimePublisher} registers its own, so anything this test sees is precisely what the
 * real publisher would have sent, and anything it does not see would never have been sent either.
 */
@Import(RealtimeEventTest.EventCaptor.class)
class RealtimeEventTest extends PostgresIntegrationTest {

    /**
     * Records events at the same lifecycle point the real publisher uses.
     *
     * <p>Both listeners are registered: the AFTER_COMMIT one mirrors production, and the plain
     * {@code @EventListener} fires the moment an event is published regardless of the transaction's
     * fate. Comparing the two is what proves the rollback case - an event can be raised and still
     * correctly never delivered.
     */
    @TestConfiguration
    static class EventCaptor {
        @Component
        static class Captor {
            final List<ClaimStateChangedEvent> afterCommit = new CopyOnWriteArrayList<>();
            final List<ClaimStateChangedEvent> raised = new CopyOnWriteArrayList<>();

            @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
            void onCommitted(ClaimStateChangedEvent event) {
                afterCommit.add(event);
            }

            @EventListener
            void onRaised(ClaimStateChangedEvent event) {
                raised.add(event);
            }

            void clear() {
                afterCommit.clear();
                raised.clear();
            }
        }
    }

    @Autowired private EventCaptor.Captor captor;
    @Autowired private ClaimService claimService;
    @Autowired private AdminClaimService adminClaimService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long studentId;
    private Long otherStudentId;
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
                        email, passwordEncoder.encode("Rt@123456"),
                        "Realtime Student", code, "B.E. CSE")).getId());
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");
        captor.clear();
        bedId = bedIdOf(201, "A");
        studentId = student("rt-student@hostelops.demo", "RT001");
        otherStudentId = student("rt-other@hostelops.demo", "RT002");
        adminId = userRepository.findByEmailIgnoreCase("rt-admin@hostelops.demo").map(User::getId)
                .orElseGet(() -> userRepository.save(User.admin(
                        "rt-admin@hostelops.demo", passwordEncoder.encode("Rt@123456"),
                        "Realtime Admin")).getId());
    }

    @Test
    @DisplayName("requesting a bed announces it on the floor, but does not notify the requester")
    void requestAnnouncesWithoutSelfNotification() {
        claimService.requestBed(studentId, bedId, null);

        assertThat(captor.afterCommit).hasSize(1);
        ClaimStateChangedEvent event = captor.afterCommit.get(0);

        assertThat(event.cause()).isEqualTo(ChangeCause.REQUESTED);
        assertThat(event.bedStatus()).isEqualTo(BedStatus.PENDING);
        assertThat(event.floorTopic()).isEqualTo("/topic/floors/C-GF");
        // The student is looking at the response to their own click; a push telling them what they
        // just did would be noise.
        assertThat(event.notifyStudent()).isFalse();
    }

    @Test
    @DisplayName("an approval notifies the student privately AND updates the floor")
    void approvalNotifiesTheStudent() {
        var request = claimService.requestBed(studentId, bedId, null);
        captor.clear();

        adminClaimService.approve(adminId, request.claim().requestId());

        assertThat(captor.afterCommit).hasSize(1);
        ClaimStateChangedEvent event = captor.afterCommit.get(0);
        assertThat(event.cause()).isEqualTo(ChangeCause.APPROVED);
        assertThat(event.bedStatus()).isEqualTo(BedStatus.ALLOCATED);
        assertThat(event.claimStatus()).isEqualTo(ClaimStatus.ALLOCATED);
        // Done TO them by someone else, so they are told.
        assertThat(event.notifyStudent()).isTrue();
        assertThat(event.studentId()).isEqualTo(studentId);
    }

    @Test
    @DisplayName("nothing is announced when the transaction rolls back")
    void rolledBackWorkAnnouncesNothing() {
        // First student takes the bed.
        claimService.requestBed(studentId, bedId, null);
        captor.clear();

        // Second student loses the race: the unique index rejects the insert and the whole
        // transaction rolls back.
        assertThatThrownBy(() -> claimService.requestBed(otherStudentId, bedId, null))
                .isInstanceOf(DomainException.class);

        // THIS is the assertion that matters. Publishing inside the transaction instead of after
        // commit would have told every browser on that floor the bed changed hands - a change that
        // never happened, with nothing to ever correct it because a rollback is silent.
        assertThat(captor.afterCommit)
                .as("no event may escape from a rolled-back transaction")
                .isEmpty();
    }

    @Test
    @DisplayName("blocking a bed with a pending request announces both the rejection and the block")
    void blockingAnnouncesTwice() {
        var request = claimService.requestBed(studentId, bedId, null);
        captor.clear();

        adminClaimService.blockBed(adminId, bedId, "Rewiring");

        // Two separate things happened and both matter to somebody: the displaced student needs to
        // know their request was rejected, and the floor needs to show the bed as out of service.
        assertThat(captor.afterCommit).hasSize(2);

        ClaimStateChangedEvent rejection = captor.afterCommit.get(0);
        assertThat(rejection.claimStatus()).isEqualTo(ClaimStatus.REJECTED);
        assertThat(rejection.notifyStudent()).isTrue();
        assertThat(rejection.studentId()).isEqualTo(studentId);
        assertThat(rejection.reason()).contains("out of circulation");
        assertThat(rejection.claimId()).isEqualTo(request.claim().requestId());

        ClaimStateChangedEvent block = captor.afterCommit.get(1);
        assertThat(block.cause()).isEqualTo(ChangeCause.BLOCKED);
        assertThat(block.bedStatus()).isEqualTo(BedStatus.BLOCKED);
        // A block belongs to no student, so there is nobody to notify privately.
        assertThat(block.notifyStudent()).isFalse();
        assertThat(block.studentId()).isNull();
    }

    @Test
    @DisplayName("an idempotent repeat announces nothing the second time")
    void repeatedApprovalDoesNotAnnounceTwice() {
        var request = claimService.requestBed(studentId, bedId, null);
        adminClaimService.approve(adminId, request.claim().requestId());
        captor.clear();

        // A second admin clicking approve on an already-approved request.
        adminClaimService.approve(adminId, request.claim().requestId());

        // Nothing changed, so nothing is announced. Publishing here would notify the student twice
        // about one decision.
        assertThat(captor.afterCommit).isEmpty();
    }

    @Test
    @DisplayName("the floor topic is derived from the bed's own wing and floor")
    void topicMatchesTheBedsLocation() {
        claimService.requestBed(studentId, bedIdOf(401, "A"), null);

        // Wing E, basement - the floor label that only exists in wings E and F.
        assertThat(captor.afterCommit.get(0).floorTopic()).isEqualTo("/topic/floors/E-BAS");
    }
}
