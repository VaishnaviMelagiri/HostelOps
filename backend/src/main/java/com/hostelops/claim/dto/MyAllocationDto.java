package com.hostelops.claim.dto;

/**
 * What the signed-in student currently holds: nothing, a pending request, or an allocation.
 *
 * <p>Phase 8 adds the roommate's name and course here, visible only once BOTH beds in a Double are
 * ALLOCATED. That field is deliberately absent for now rather than present-and-null, so no client
 * can start depending on it before the visibility rule that guards it exists.
 */
public record MyAllocationDto(State state, ClaimDto claim) {

    public enum State {
        /** No request and no allocation - free to request a bed. */
        NONE,
        /** A request is waiting on an admin decision. */
        PENDING,
        /** An admin approved it; the bed is theirs. */
        ALLOCATED
    }

    public static MyAllocationDto none() {
        return new MyAllocationDto(State.NONE, null);
    }

    public static MyAllocationDto of(ClaimDto claim) {
        State state = claim.status() == com.hostelops.claim.ClaimStatus.ALLOCATED
                ? State.ALLOCATED
                : State.PENDING;
        return new MyAllocationDto(state, claim);
    }
}
