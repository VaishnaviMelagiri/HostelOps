package com.hostelops.room;

import com.hostelops.room.dto.WingFloorAggregate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RoomRepository extends JpaRepository<Room, Long> {

    /**
     * All rooms on one floor of one wing, with their beds already loaded.
     *
     * <p>{@code JOIN FETCH} is the important part. Without it, Hibernate would fetch the rooms with
     * one query and then fire a further query for each room's beds when the code touched them -
     * 23 queries for a 22-room floor. That is the "N+1 select problem", the most common performance
     * bug in JPA applications, and it is invisible until you look at the SQL log. JOIN FETCH loads
     * everything in a single query instead.
     *
     * <p>{@code DISTINCT} is needed because joining a room to its two beds returns the room twice.
     */
    @Query("""
            SELECT DISTINCT r FROM Room r
            LEFT JOIN FETCH r.beds
            WHERE r.wing = :wing AND r.floor = :floor
            ORDER BY r.positionInFloor
            """)
    List<Room> findFloorWithBeds(@Param("wing") String wing, @Param("floor") String floor);

    /**
     * Room and bed counts per wing per floor, computed by the database.
     *
     * <p>A GROUP BY in SQL rather than loading 404 rooms and counting them in Java: the database
     * returns roughly 26 rows instead of 404 objects, and it is the kind of work databases are
     * built for.
     *
     * <p>{@code SELECT new ...} is a JPQL constructor expression - each result row is passed to the
     * WingFloorAggregate constructor, so the method returns typed records rather than Object[].
     */
    @Query("""
            SELECT new com.hostelops.room.dto.WingFloorAggregate(
                r.wing, r.block, r.roomType, r.bathroomType, r.floor, r.floorLevel,
                count(r), sum(r.capacity))
            FROM Room r
            GROUP BY r.wing, r.block, r.roomType, r.bathroomType, r.floor, r.floorLevel
            ORDER BY r.wing, r.floorLevel
            """)
    List<WingFloorAggregate> aggregateByWingAndFloor();

    boolean existsByWing(String wing);
}
