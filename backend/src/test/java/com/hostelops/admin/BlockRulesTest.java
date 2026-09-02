package com.hostelops.admin;

import com.hostelops.claim.AdminClaimService;
import com.hostelops.claim.ClaimService;
import com.hostelops.claim.ClaimStatus;
import com.hostelops.claim.dto.BedActionResultDto;
import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.room.dto.BedStatus;
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

/** Blocking and unblocking beds for maintenance, including what blocking is NOT allowed to do. */
class BlockRulesTest extends PostgresIntegrationTest {

    @Autowired private AdminClaimService adminClaimService;
    @Autowired private ClaimService claimService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long adminId;
    private Long studentId;
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

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");
        bedId = bedIdOf(401, "A");

        adminId = userRepository.findByEmailIgnoreCase("block-admin@hostelops.demo")
                .map(User::getId)
                .orElseGet(() -> userRepository.save(User.admin(
                        "block-admin@hostelops.demo", passwordEncoder.encode("Block@12345"),
                        "Block Admin")).getId());

        studentId = userRepository.findByEmailIgnoreCase("block-student@hostelops.demo")
                .map(User::getId)
                .orElseGet(() -> userRepository.save(User.student(
                        "block-student@hostelops.demo", passwordEncoder.encode("Block@12345"),
                        "Block Student", "BLK001", "B.E. CSE")).getId());
    }

    @Test
    @DisplayName("blocking an available bed makes it BLOCKED and unrequestable")
    void blockAvailableBed() {
        BedActionResultDto result = adminClaimService.blockBed(adminId, bedId, "Plumbing repair");

        assertThat(result.status()).isEqualTo(BedStatus.BLOCKED);
        assertThat(result.alreadyHandled()).isFalse();
        assertThat(result.autoRejectedRequestId()).isNull();
        assertThat(statusOf(bedId)).isEqualTo("BLOCKED");

        // A student cannot take a blocked bed - and it is the per-bed index that stops them, the
        // same one that stops two students colliding. Blocks live in the same table precisely so
        // one index covers both races.
        assertThatThrownBy(() -> claimService.requestBed(studentId, bedId, null))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.BED_NOT_AVAILABLE);
    }

    @Test
    @DisplayName("blocking a bed with a PENDING request auto-rejects it with a system reason")
    void blockingAutoRejectsAPendingRequest() {
        var request = claimService.requestBed(studentId, bedId, null);
        assertThat(statusOf(bedId)).isEqualTo("PENDING");

        BedActionResultDto result = adminClaimService.blockBed(adminId, bedId, "Ceiling leak");

        // The student's request is resolved, not left dangling on a bed that will never be approved.
        assertThat(result.autoRejectedRequestId()).isEqualTo(request.claim().requestId());
        assertThat(statusOf(bedId)).isEqualTo("BLOCKED");

        String rejectedReason = jdbcTemplate.queryForObject(
                "SELECT decision_reason FROM bed_claims WHERE id = ?",
                String.class, request.claim().requestId());
        assertThat(rejectedReason).contains("out of circulation");

        String rejectedStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bed_claims WHERE id = ?",
                String.class, request.claim().requestId());
        assertThat(rejectedStatus).isEqualTo(ClaimStatus.REJECTED.name());

        // And the student is free again straight away - the auto-reject released their slot in the
        // per-student index, so losing this bed does not lock them out of the whole system.
        Long otherBed = bedIdOf(402, "A");
        assertThat(claimService.requestBed(studentId, otherBed, null).created()).isTrue();
    }

    @Test
    @DisplayName("blocking an ALLOCATED bed is refused with an explanation, not silently ignored")
    void cannotBlockAnAllocatedBed() {
        var request = claimService.requestBed(studentId, bedId, null);
        adminClaimService.approve(adminId, request.claim().requestId());
        assertThat(statusOf(bedId)).isEqualTo("ALLOCATED");

        assertThatThrownBy(() -> adminClaimService.blockBed(adminId, bedId, "Repairs"))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> {
                    DomainException domain = (DomainException) e;
                    assertThat(domain.errorCode()).isEqualTo(ErrorCode.BED_ALLOCATED_CANNOT_BLOCK);
                    // The message says WHY it is out of scope, so an admin is not left guessing
                    // whether it is a bug or a rule.
                    assertThat(domain.getMessage()).contains("eviction");
                });

        // Nothing changed: the student still has their bed.
        assertThat(statusOf(bedId)).isEqualTo("ALLOCATED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE bed_id = ?", Integer.class, bedId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("blocking twice is idempotent")
    void blockingTwiceIsIdempotent() {
        adminClaimService.blockBed(adminId, bedId, "First");
        BedActionResultDto second = adminClaimService.blockBed(adminId, bedId, "Second");

        assertThat(second.alreadyHandled()).isTrue();
        assertThat(second.status()).isEqualTo(BedStatus.BLOCKED);
        // One block row, not two - so unblocking once is enough to free the bed.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE bed_id = ? AND status = 'BLOCKED'",
                Integer.class, bedId)).isEqualTo(1);
    }

    @Test
    @DisplayName("unblocking returns the bed to AVAILABLE and keeps the block as history")
    void unblockingReleasesTheBed() {
        adminClaimService.blockBed(adminId, bedId, "Painting");

        BedActionResultDto result = adminClaimService.unblockBed(adminId, bedId, "Painting done");

        assertThat(result.status()).isEqualTo(BedStatus.AVAILABLE);
        assertThat(result.alreadyHandled()).isFalse();
        assertThat(statusOf(bedId)).isEqualTo("AVAILABLE");

        // The block period stays in the table, attributable at both ends.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE bed_id = ? AND status = 'UNBLOCKED'",
                Integer.class, bedId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decided_by IS NOT NULL AND created_by IS NOT NULL FROM bed_claims "
                        + "WHERE bed_id = ? AND status = 'UNBLOCKED'", Boolean.class, bedId))
                .isTrue();

        // And the bed is requestable again.
        assertThat(claimService.requestBed(studentId, bedId, null).created()).isTrue();
    }

    @Test
    @DisplayName("unblocking a bed that is not blocked is refused or reported as already done")
    void unblockingAnUnblockedBed() {
        // Never blocked at all: the bed is already available, which is what the caller wanted.
        assertThat(adminClaimService.unblockBed(adminId, bedId, "n/a").alreadyHandled()).isTrue();

        // Held by a student: that is not a block, and pretending to "unblock" it would be wrong.
        claimService.requestBed(studentId, bedId, null);
        assertThatThrownBy(() -> adminClaimService.unblockBed(adminId, bedId, "n/a"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).errorCode())
                .isEqualTo(ErrorCode.BED_NOT_BLOCKED);
    }

    @Test
    @DisplayName("a rejected request frees the bed for someone else")
    void rejectionFreesTheBed() {
        var request = claimService.requestBed(studentId, bedId, null);
        adminClaimService.reject(adminId, request.claim().requestId(), "Wing reserved for first-years");

        assertThat(statusOf(bedId)).isEqualTo("AVAILABLE");

        Long otherStudent = userRepository.findByEmailIgnoreCase("second-student@hostelops.demo")
                .map(User::getId)
                .orElseGet(() -> userRepository.save(User.student(
                        "second-student@hostelops.demo", passwordEncoder.encode("Sec@12345"),
                        "Second Student", "SEC001", "B.E. CSE")).getId());

        assertThat(claimService.requestBed(otherStudent, bedId, null).created()).isTrue();
        // Two rows on the bed: one rejected (history) and one live. The index allows this because
        // only the live one is in it.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bed_claims WHERE bed_id = ?", Integer.class, bedId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("rejecting records the reason the student will see")
    void rejectionReasonIsRecorded() {
        var request = claimService.requestBed(studentId, bedId, null);
        adminClaimService.reject(adminId, request.claim().requestId(), "Reserved for final years");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT decision_reason FROM bed_claims WHERE id = ?",
                String.class, request.claim().requestId()))
                .isEqualTo("Reserved for final years");
    }

    @Test
    @DisplayName("the pending queue shows the student's identity, oldest request first")
    void pendingQueueShowsIdentities() {
        claimService.requestBed(studentId, bedId, null);

        var queue = adminClaimService.pendingQueue(0, 25);

        assertThat(queue.total()).isEqualTo(1);
        var row = queue.rows().get(0);
        assertThat(row.student().fullName()).isEqualTo("Block Student");
        assertThat(row.student().studentCode()).isEqualTo("BLK001");
        assertThat(row.roomNumber()).isEqualTo(401);
        assertThat(row.bedLabel()).isEqualTo("A");
        assertThat(row.pendingAgeSeconds()).isGreaterThanOrEqualTo(0);
        assertThat(row.expiresAt()).isNotNull();
    }
}
