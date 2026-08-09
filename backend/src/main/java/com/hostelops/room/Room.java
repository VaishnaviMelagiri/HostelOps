package com.hostelops.room;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * A room in the real building. Maps to the {@code rooms} table.
 *
 * <p>{@code roomType} and {@code bathroomType} are plain Strings rather than Java enums. The
 * database stores them as {@code 'Single'} / {@code 'Double'} - mixed case, matching the source
 * data and the floor-plan posters. A Java enum would be {@code SINGLE}, so mapping one to the other
 * needs an AttributeConverter, and the frontend wants the original strings anyway. Keeping them as
 * Strings means one spelling from poster to database to JSON, with nothing to translate.
 */
@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_number", nullable = false)
    private Integer roomNumber;

    /** 'A' through 'F'. CHAR(1) in the database - see the note on {@link Bed#getBedLabel()}. */
    @Column(nullable = false, length = 1)
    @JdbcTypeCode(SqlTypes.CHAR)
    private String wing;

    @Column(nullable = false)
    private String block;

    @Column(nullable = false, length = 4)
    private String floor;

    /** Ordering WITHIN a wing. A-D start at GF=0; E-F have a basement, so BAS=0 and GF=1. */
    @Column(name = "floor_level", nullable = false)
    private Short floorLevel;

    @Column(name = "position_in_floor", nullable = false)
    private Short positionInFloor;

    @Column(name = "room_type", nullable = false, length = 8)
    private String roomType;

    @Column(name = "bathroom_type", nullable = false, length = 8)
    private String bathroomType;

    /** 1 for a Single, 2 for a Double. The database enforces that it matches roomType. */
    @Column(nullable = false)
    private Short capacity;

    /**
     * The beds in this room.
     *
     * <p>{@code mappedBy = "room"} means Bed owns the foreign key - this side is just the mirror.
     * {@code LAZY} means the beds are not loaded until something touches them, so a query that only
     * needs room details does not silently drag in 576 bed rows. The floor-map query asks for them
     * explicitly with a JOIN FETCH.
     */
    @OneToMany(mappedBy = "room", fetch = FetchType.LAZY)
    @OrderBy("bedLabel ASC")
    private List<Bed> beds = new ArrayList<>();

    protected Room() {
        // Required by JPA.
    }

    public Long getId() {
        return id;
    }

    public Integer getRoomNumber() {
        return roomNumber;
    }

    public String getWing() {
        return wing;
    }

    public String getBlock() {
        return block;
    }

    public String getFloor() {
        return floor;
    }

    public short getFloorLevel() {
        return floorLevel;
    }

    public short getPositionInFloor() {
        return positionInFloor;
    }

    public String getRoomType() {
        return roomType;
    }

    public String getBathroomType() {
        return bathroomType;
    }

    public short getCapacity() {
        return capacity;
    }

    public List<Bed> getBeds() {
        return beds;
    }
}
