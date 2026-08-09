package com.hostelops.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One bed. This is the unit everything in HostelOps is about: requests, approvals and blocks all
 * happen per bed, never per room.
 *
 * <p>A Single room has one bed ('A'); a Double has two ('A' and 'B'). Because the rules are written
 * against beds, both room types run through identical code - a Single is simply a room that happens
 * to have one bed, and there is no special case anywhere.
 *
 * <p>Note there is no {@code status} field. See the {@code bed_status} view in V2: status is derived
 * from whether a live claim exists, never stored here.
 */
@Entity
@Table(name = "beds")
public class Bed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    /**
     * 'A' or 'B'.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.CHAR)} is required because the column is {@code CHAR(1)},
     * which Postgres reports as {@code bpchar}. A plain Java String maps to {@code varchar} by
     * default, and {@code ddl-auto: validate} refuses to start on the mismatch:
     * <em>"wrong column type encountered in column [bed_label]; found [bpchar], but expecting
     * [varchar(1)]"</em>. This annotation tells Hibernate the JDBC type the column really has.
     *
     * <p>The entity is being corrected to match the database, not the other way round - the schema
     * is owned by Flyway and is the source of truth.
     */
    @Column(name = "bed_label", nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String bedLabel;

    protected Bed() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public Room getRoom() {
        return room;
    }

    public String getBedLabel() {
        return bedLabel;
    }
}
