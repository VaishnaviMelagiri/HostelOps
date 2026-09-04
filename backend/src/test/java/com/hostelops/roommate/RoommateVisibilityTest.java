package com.hostelops.roommate;

import com.hostelops.claim.AdminClaimService;
import com.hostelops.claim.ClaimService;
import com.hostelops.claim.dto.MyAllocationDto;
import com.hostelops.claim.dto.RoommateState;
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

/**
 * The roommate rule - the most privacy-sensitive behaviour in the project.
 *
 * <p>A name and course are shown only once BOTH beds in a Double are ALLOCATED. The case worth the
 * most attention is the near miss: the other bed merely PENDING must reveal nothing, because
 * otherwise any student could request the free bed in a room, read the occupant's name, and cancel.
 */
class RoommateVisibilityTest extends PostgresIntegrationTest {

    @Autowired private ClaimService claimService;
    @Autowired private AdminClaimService adminClaimService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long asha;
    private Long priya;
    private Long adminId;
    private Long doubleBedA;
    private Long doubleBedB;
    private Long singleBed;

    private Long bedIdOf(int room, String label) {
        return jdbcTemplate.queryForObject(
                "SELECT b.id FROM beds b JOIN rooms r ON r.id = b.room_id "
                        + "WHERE r.room_number = ? AND b.bed_label = ?", Long.class, room, label);
    }

    private Long student(String email, String name, String code, String course) {
        return userRepository.findByEmailIgnoreCase(email).map(User::getId)
                .orElseGet(() -> userRepository.save(User.student(
                        email, passwordEncoder.encode("Rm@123456"), name, code, course)).getId());
    }

    private void approveFor(Long studentId) {
        Long requestId = jdbcTemplate.queryForObject(
                "SELECT id FROM bed_claims WHERE student_id = ? AND status = 'PENDING'",
                Long.class, studentId);
        adminClaimService.approve(adminId, requestId);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM bed_claims");
        // Room 101 is a Double in wing B; room 1 is a Single in wing A.
        doubleBedA = bedIdOf(101, "A");
        doubleBedB = bedIdOf(101, "B");
        singleBed = bedIdOf(1, "A");

        asha = student("asha@hostelops.demo", "Asha R", "RM-CS-001", "B.E. Computer Science");
        priya = student("priya@hostelops.demo", "Priya N", "RM-EC-042", "B.E. Electronics");
        adminId = userRepository.findByEmailIgnoreCase("rm-admin@hostelops.demo").map(User::getId)
                .orElseGet(() -> userRepository.save(User.admin(
                        "rm-admin@hostelops.demo", passwordEncoder.encode("Rm@123456"),
                        "Roommate Admin")).getId());
    }

    @Test
    @DisplayName("a merely PENDING neighbour reveals NOTHING - the case the rule exists for")
    void pendingNeighbourIsNeverRevealed() {
        claimService.requestBed(asha, doubleBedA, null);
        approveFor(asha);

        // Priya requests the second bed but has not been approved.
        claimService.requestBed(priya, doubleBedB, null);

        MyAllocationDto ashaSees = claimService.myAllocation(asha);

        // Asha learns the other bed is being asked for - a fact already visible on the map - but
        // not by whom. Without this, requesting a bed and cancelling would be a way to look up
        // who lives in any room.
        assertThat(ashaSees.roommateState()).isEqualTo(RoommateState.PENDING);
        assertThat(ashaSees.roommate()).isNull();
    }

    @Test
    @DisplayName("both beds ALLOCATED -> each sees the other's name and course, and nothing else")
    void bothConfirmedRevealsNameAndCourse() {
        claimService.requestBed(asha, doubleBedA, null);
        approveFor(asha);
        claimService.requestBed(priya, doubleBedB, null);
        approveFor(priya);

        MyAllocationDto ashaSees = claimService.myAllocation(asha);
        MyAllocationDto priyaSees = claimService.myAllocation(priya);

        assertThat(ashaSees.roommateState()).isEqualTo(RoommateState.ALLOCATED);
        assertThat(ashaSees.roommate()).isNotNull();
        assertThat(ashaSees.roommate().fullName()).isEqualTo("Priya N");
        assertThat(ashaSees.roommate().course()).isEqualTo("B.E. Electronics");

        // Symmetric, and simultaneous: the rule is evaluated identically for both from the same
        // data, so they flip together at the second approval rather than one before the other.
        assertThat(priyaSees.roommate().fullName()).isEqualTo("Asha R");
        assertThat(priyaSees.roommate().course()).isEqualTo("B.E. Computer Science");

        // Two fields on the record. Not "no email because we remembered to omit it" - there is no
        // field to hold one, and the query cannot return one.
        assertThat(RoommateStateFields.of(ashaSees)).containsExactly("fullName", "course");
    }

    /** Reflects over the roommate record to assert its shape, not just its values. */
    private static final class RoommateStateFields {
        static java.util.List<String> of(MyAllocationDto dto) {
            return java.util.Arrays.stream(dto.roommate().getClass().getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
        }
    }

    @Test
    @DisplayName("an empty second bed reports EMPTY, not a name")
    void emptyNeighbour() {
        claimService.requestBed(asha, doubleBedA, null);
        approveFor(asha);

        MyAllocationDto sees = claimService.myAllocation(asha);
        assertThat(sees.roommateState()).isEqualTo(RoommateState.EMPTY);
        assertThat(sees.roommate()).isNull();
    }

    @Test
    @DisplayName("a blocked second bed reports BLOCKED")
    void blockedNeighbour() {
        claimService.requestBed(asha, doubleBedA, null);
        approveFor(asha);
        adminClaimService.blockBed(adminId, doubleBedB, "Window repair");

        MyAllocationDto sees = claimService.myAllocation(asha);
        assertThat(sees.roommateState()).isEqualTo(RoommateState.BLOCKED);
        assertThat(sees.roommate()).isNull();
    }

    @Test
    @DisplayName("a Single room has no roommate concept at all")
    void singleRoomHasNoRoommate() {
        claimService.requestBed(asha, singleBed, null);
        approveFor(asha);

        MyAllocationDto sees = claimService.myAllocation(asha);
        assertThat(sees.state()).isEqualTo(MyAllocationDto.State.ALLOCATED);
        assertThat(sees.roommateState()).isEqualTo(RoommateState.NONE);
        assertThat(sees.roommate()).isNull();
    }

    @Test
    @DisplayName("a student with only a PENDING request gets no roommate information at all")
    void pendingStudentSeesNothing() {
        claimService.requestBed(asha, doubleBedA, null);
        approveFor(asha);
        claimService.requestBed(priya, doubleBedB, null);

        // Priya is the one waiting. She has no room yet, so there is no roommate to speak of -
        // roommateState is absent entirely rather than reporting on a room she may never get.
        MyAllocationDto priyaSees = claimService.myAllocation(priya);
        assertThat(priyaSees.state()).isEqualTo(MyAllocationDto.State.PENDING);
        assertThat(priyaSees.roommateState()).isNull();
        assertThat(priyaSees.roommate()).isNull();
    }

    @Test
    @DisplayName("if the roommate's allocation ends, visibility ends with it")
    void visibilityFollowsTheCurrentState() {
        claimService.requestBed(asha, doubleBedA, null);
        approveFor(asha);
        claimService.requestBed(priya, doubleBedB, null);
        approveFor(priya);
        assertThat(claimService.myAllocation(asha).roommate()).isNotNull();

        // Stand in for a future eviction workflow (out of scope in this build) by ending Priya's
        // allocation directly. Nothing is cached and no "revealed" flag was stored, so visibility
        // is recomputed from the current state and simply stops.
        jdbcTemplate.update("""
                UPDATE bed_claims SET status='CANCELLED', decided_at=now(), decided_by=?,
                       expires_at=NULL
                 WHERE student_id=? AND status='ALLOCATED'
                """, adminId, priya);

        MyAllocationDto ashaSees = claimService.myAllocation(asha);
        assertThat(ashaSees.roommateState()).isEqualTo(RoommateState.EMPTY);
        assertThat(ashaSees.roommate()).isNull();
    }
}
