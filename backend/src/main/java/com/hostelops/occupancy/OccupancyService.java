package com.hostelops.occupancy;

import com.hostelops.occupancy.dto.OccupancyDto;
import com.hostelops.occupancy.dto.WingOccupancyDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Bed counts by status, per wing and overall.
 *
 * <p>One GROUP BY over the {@code bed_status} view rather than loading 576 beds and counting them
 * in Java. The database returns roughly two dozen rows; counting is exactly what it is built for.
 *
 * <p>Uses JdbcTemplate for the same reason {@code BedStatusRepository} does: bed_status is a view
 * with no identity of its own, and mapping it as a JPA entity would be pretending otherwise.
 */
@Service
public class OccupancyService {

    private final JdbcTemplate jdbcTemplate;

    public OccupancyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public OccupancyDto snapshot() {
        // FILTER (WHERE ...) is Postgres's conditional aggregate: it counts only the rows matching
        // each condition, so one pass over the data produces all four counts per wing rather than
        // four separate queries. Cleaner than the SUM(CASE WHEN ... THEN 1 ELSE 0 END) idiom.
        List<WingOccupancyDto> wings = jdbcTemplate.query("""
                SELECT r.wing,
                       min(r.block)          AS block,
                       min(r.room_type)      AS room_type,
                       min(r.bathroom_type)  AS bathroom_type,
                       count(*)                                          AS total_beds,
                       count(*) FILTER (WHERE bs.status = 'AVAILABLE')   AS available,
                       count(*) FILTER (WHERE bs.status = 'PENDING')     AS pending,
                       count(*) FILTER (WHERE bs.status = 'ALLOCATED')   AS allocated,
                       count(*) FILTER (WHERE bs.status = 'BLOCKED')     AS blocked
                FROM bed_status bs
                JOIN rooms r ON r.id = bs.room_id
                GROUP BY r.wing
                ORDER BY r.wing
                """,
                (rs, rowNum) -> new WingOccupancyDto(
                        rs.getString("wing"),
                        rs.getString("block"),
                        rs.getString("room_type"),
                        rs.getString("bathroom_type"),
                        rs.getInt("total_beds"),
                        rs.getInt("available"),
                        rs.getInt("pending"),
                        rs.getInt("allocated"),
                        rs.getInt("blocked")));

        // Totals summed from the same rows, so the header can never disagree with the breakdown
        // beneath it - which a second query could, if a request landed between the two.
        List<WingOccupancyDto> all = new ArrayList<>(wings);
        return new OccupancyDto(
                all.stream().mapToInt(WingOccupancyDto::totalBeds).sum(),
                all.stream().mapToInt(WingOccupancyDto::available).sum(),
                all.stream().mapToInt(WingOccupancyDto::pending).sum(),
                all.stream().mapToInt(WingOccupancyDto::allocated).sum(),
                all.stream().mapToInt(WingOccupancyDto::blocked).sum(),
                all);
    }

    /** Beds currently out of service, for the admin's block/unblock panel. */
    @Transactional(readOnly = true)
    public List<BlockedBedDto> blockedBeds() {
        return jdbcTemplate.query("""
                SELECT bs.bed_id, r.room_number, bs.bed_label, r.wing, r.floor,
                       c.decision_reason, c.created_at
                FROM bed_status bs
                JOIN rooms r ON r.id = bs.room_id
                JOIN bed_claims c ON c.id = bs.claim_id
                WHERE bs.status = 'BLOCKED'
                ORDER BY r.wing, r.room_number, bs.bed_label
                """,
                (rs, rowNum) -> new BlockedBedDto(
                        rs.getLong("bed_id"),
                        rs.getInt("room_number"),
                        rs.getString("bed_label"),
                        rs.getString("wing"),
                        rs.getString("floor"),
                        rs.getString("decision_reason"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    /** One out-of-service bed. */
    public record BlockedBedDto(
            Long bedId,
            Integer roomNumber,
            String bedLabel,
            String wing,
            String floor,
            String reason,
            java.time.Instant blockedAt) {
    }
}
