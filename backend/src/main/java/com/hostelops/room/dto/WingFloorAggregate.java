package com.hostelops.room.dto;

/**
 * One row of "how many rooms and beds are on this floor of this wing", straight from a GROUP BY.
 *
 * <p>This is a JPQL <em>constructor expression</em> target: the query says
 * {@code SELECT new WingFloorAggregate(...)} and Hibernate calls this constructor per row. That
 * gives typed objects out of an aggregate query instead of {@code Object[]} arrays that every
 * caller has to cast by index.
 *
 * <p>{@code count()} and {@code sum()} come back as Long in JPQL, hence the types.
 */
public record WingFloorAggregate(
        String wing,
        String block,
        String roomType,
        String bathroomType,
        String floor,
        Short floorLevel,
        Long roomCount,
        Long bedCount) {
}
