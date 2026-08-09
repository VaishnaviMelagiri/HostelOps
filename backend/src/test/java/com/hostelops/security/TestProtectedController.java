package com.hostelops.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A test-only controller, existing purely so {@link SecurityRulesTest} can exercise the real
 * authorization machinery.
 *
 * <p>It lives in the test sources and is never packaged. The alternative - waiting for Phase 3's
 * real endpoints - would mean the permission model shipped completely unverified.
 */
@RestController
@RequestMapping("/api/test")
public class TestProtectedController {

    @GetMapping("/room-read")
    @PreAuthorize("hasAuthority('ROOM_READ')")
    public String roomRead() {
        return "ok";
    }

    @PostMapping("/request-create")
    @PreAuthorize("hasAuthority('REQUEST_CREATE')")
    public String requestCreate() {
        return "ok";
    }

    @PostMapping("/approve")
    @PreAuthorize("hasAuthority('REQUEST_APPROVE')")
    public String approve() {
        return "ok";
    }

    @GetMapping("/my-allocation")
    @PreAuthorize("hasAuthority('ALLOCATION_READ_OWN')")
    public String myAllocation() {
        return "ok";
    }
}
