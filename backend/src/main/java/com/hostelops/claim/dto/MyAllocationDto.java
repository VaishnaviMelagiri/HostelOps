package com.hostelops.claim.dto;

/**
 * What the signed-in student currently holds: nothing, a pending request, or an allocation.
 *
 * <p>{@code roommate} is present ONLY when {@code roommateState} is ALLOCATED - that is, once both
 * beds in a Double have been confirmed by an admin. It is absent in every other case, not null:
 * with {@code default-property-inclusion: non_null} the field does not appear in the JSON at all,
 * so a client cannot read a name that was never sent.
 *
 * @param roommateState always present for an allocation, so the UI can say "the other bed is
 *                      awaiting approval" without saying whose
 */
public record MyAllocationDto(
        State state,
        ClaimDto claim,
        RoommateState roommateState,
        RoommateDto roommate) {

    public enum State {
        /** No request and no allocation - free to request a bed. */
        NONE,
        /** A request is waiting on an admin decision. */
        PENDING,
        /** An admin approved it; the bed is theirs. */
        ALLOCATED
    }

    public static MyAllocationDto none() {
        return new MyAllocationDto(State.NONE, null, null, null);
    }

    /** A pending request. No roommate information at all - they do not have a room yet. */
    public static MyAllocationDto pending(ClaimDto claim) {
        return new MyAllocationDto(State.PENDING, claim, null, null);
    }

    /** An allocation, with whatever may truthfully be said about the other bed. */
    public static MyAllocationDto allocated(ClaimDto claim, RoommateState roommateState,
                                            RoommateDto roommate) {
        return new MyAllocationDto(State.ALLOCATED, claim, roommateState, roommate);
    }
}
