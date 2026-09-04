package com.hostelops.occupancy;

import com.hostelops.occupancy.dto.OccupancyDto;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Aggregate views for the admin dashboard. Counts only - no identities. */
@RestController
@RequestMapping("/api/admin")
public class OccupancyController {

    private final OccupancyService occupancyService;

    public OccupancyController(OccupancyService occupancyService) {
        this.occupancyService = occupancyService;
    }

    @GetMapping("/occupancy")
    @PreAuthorize("hasAuthority('OCCUPANCY_READ')")
    public OccupancyDto occupancy() {
        return occupancyService.snapshot();
    }

    /**
     * Beds currently out of service.
     *
     * <p>Gated on BED_UNBLOCK rather than OCCUPANCY_READ: the only reason to fetch this list is to
     * act on it, so it asks for the permission that acting requires.
     */
    @GetMapping("/blocked-beds")
    @PreAuthorize("hasAuthority('BED_UNBLOCK')")
    public List<OccupancyService.BlockedBedDto> blockedBeds() {
        return occupancyService.blockedBeds();
    }
}
