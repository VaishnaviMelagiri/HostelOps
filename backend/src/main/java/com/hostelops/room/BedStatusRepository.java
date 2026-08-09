package com.hostelops.room;

import com.hostelops.room.dto.BedStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads the derived {@code bed_status} view.
 *
 * <p>Uses JdbcTemplate rather than JPA, and that is a deliberate choice worth explaining. bed_status
 * is a database VIEW, not a table - nothing is ever written to it, and it has no identity of its own
 * to save or update. Mapping it as a JPA entity would mean pretending it is something it is not, and
 * would need extra Hibernate annotations to stop it trying to manage the "rows". A plain query that
 * returns bed id and status is the whole requirement.
 *
 * <p>In Phase 3 every bed comes back AVAILABLE, because no requests exist yet. The query is written
 * for the finished system, so Phases 4-6 light it up without touching this class.
 */
@Repository
public class BedStatusRepository {

    private final JdbcTemplate jdbcTemplate;

    public BedStatusRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Status of every bed on one floor, keyed by bed id.
     *
     * <p>One query for the whole floor rather than one per bed - the caller then does map lookups
     * in memory, which cost nothing.
     */
    public Map<Long, BedStatus> statusesForFloor(String wing, String floor) {
        Map<Long, BedStatus> byBedId = new HashMap<>();

        jdbcTemplate.query("""
                SELECT bs.bed_id, bs.status
                FROM bed_status bs
                JOIN rooms r ON r.id = bs.room_id
                WHERE r.wing = ? AND r.floor = ?
                """,
                rs -> {
                    // A ResultSetExtractor-style callback: fills the map as rows stream past,
                    // so the whole result set is never held in a list first.
                    byBedId.put(rs.getLong("bed_id"), BedStatus.valueOf(rs.getString("status")));
                },
                wing, floor);

        return byBedId;
    }
}
