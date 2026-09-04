package com.hostelops.claim.dto;

/**
 * The state of the OTHER bed in a Double room, from one occupant's point of view.
 *
 * <p>Exists so the UI can say something truthful about the second bed without revealing anyone.
 * "Awaiting approval" is useful to know; who is awaiting approval is not yours to know yet.
 */
public enum RoommateState {

    /** A Single room - there is no other bed. */
    NONE,

    /** The other bed is free. */
    EMPTY,

    /**
     * Someone has requested the other bed but no admin has approved it.
     *
     * <p><strong>This is the case the whole rule exists for.</strong> If a name were shown here,
     * any student could request the free bed in a room, read the occupant's name, and cancel -
     * turning the map into a directory of who lives where. Requiring ALLOCATED means an admin has
     * affirmatively confirmed both people before either learns anything about the other.
     */
    PENDING,

    /** The other bed is out of service for maintenance. */
    BLOCKED,

    /** Both beds are confirmed. Only now is a name shown, and only a name and a course. */
    ALLOCATED
}
