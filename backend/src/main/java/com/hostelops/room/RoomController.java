package com.hostelops.room;

import com.hostelops.room.dto.FloorRoomsDto;
import com.hostelops.room.dto.WingSummaryDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Browsing the building. Read-only - no request or approve actions exist yet.
 *
 * <p>Both endpoints require {@code ROOM_READ}, which all three roles hold, so a STUDENT, an ADMIN
 * and a GUEST receive byte-identical responses. That is not laziness: the payload contains no
 * student identities at all, so there is nothing to redact per role and no risk of the redaction
 * being forgotten in one branch. Admins get identities from the pending-queue endpoint in Phase 5,
 * which keeps identity disclosure in exactly one place.
 *
 * <p>{@code @PreAuthorize} is checked before the method body runs. An unauthenticated caller is
 * already stopped earlier by the URL rules in SecurityConfig; this is the second, narrower layer.
 */
@RestController
@RequestMapping("/api/wings")
public class RoomController {

    private final RoomQueryService roomQueryService;

    public RoomController(RoomQueryService roomQueryService) {
        this.roomQueryService = roomQueryService;
    }

    /** The six wings, for the picker. */
    @GetMapping
    @PreAuthorize("hasAuthority('ROOM_READ')")
    public List<WingSummaryDto> listWings() {
        return roomQueryService.listWings();
    }

    /**
     * One floor of one wing.
     *
     * <p>{@code @PathVariable} binds the {wing} and {floor} segments of the URL to these parameters,
     * so {@code GET /api/wings/B/floors/FF/rooms} arrives as ("B", "FF").
     */
    @GetMapping("/{wing}/floors/{floor}/rooms")
    @PreAuthorize("hasAuthority('ROOM_READ')")
    public FloorRoomsDto getFloor(@PathVariable String wing, @PathVariable String floor) {
        return roomQueryService.getFloor(wing, floor);
    }
}
