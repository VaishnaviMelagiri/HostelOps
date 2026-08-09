package com.hostelops.room;

import com.hostelops.common.DomainException;
import com.hostelops.common.ErrorCode;
import com.hostelops.room.dto.BedStateDto;
import com.hostelops.room.dto.BedStatus;
import com.hostelops.room.dto.FloorRoomsDto;
import com.hostelops.room.dto.GridDto;
import com.hostelops.room.dto.RoomCellDto;
import com.hostelops.room.dto.RoomStatus;
import com.hostelops.room.dto.WingFloorAggregate;
import com.hostelops.room.dto.WingSummaryDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only queries behind the floor map.
 *
 * <p>Every method is {@code @Transactional(readOnly = true)}. That flag lets the database skip work
 * it only needs for writes, and - more usefully here - makes Hibernate skip dirty checking, so a
 * loaded entity cannot be accidentally modified and flushed back. On a browse-only screen that is
 * exactly the guarantee wanted.
 */
@Service
@Transactional(readOnly = true)
public class RoomQueryService {

    private final RoomRepository roomRepository;
    private final BedStatusRepository bedStatusRepository;

    public RoomQueryService(RoomRepository roomRepository, BedStatusRepository bedStatusRepository) {
        this.roomRepository = roomRepository;
        this.bedStatusRepository = bedStatusRepository;
    }

    /**
     * All six wings with their floors and totals.
     *
     * <p>The database returns one row per wing+floor; this assembles them into one entry per wing.
     * A LinkedHashMap preserves the order the query produced (wing, then floor level), so floors
     * come out bottom-to-top - BAS, GF, FF, SF, TF - which is how someone thinks about a building.
     */
    public List<WingSummaryDto> listWings() {
        Map<String, List<WingFloorAggregate>> byWing = new LinkedHashMap<>();
        for (WingFloorAggregate row : roomRepository.aggregateByWingAndFloor()) {
            byWing.computeIfAbsent(row.wing(), key -> new ArrayList<>()).add(row);
        }

        List<WingSummaryDto> summaries = new ArrayList<>();
        byWing.forEach((wing, floors) -> {
            floors.sort(Comparator.comparing(WingFloorAggregate::floorLevel));
            WingFloorAggregate first = floors.get(0);

            int totalRooms = floors.stream().mapToInt(f -> f.roomCount().intValue()).sum();
            int totalBeds = floors.stream().mapToInt(f -> f.bedCount().intValue()).sum();

            summaries.add(new WingSummaryDto(
                    wing,
                    first.block(),
                    first.roomType(),
                    first.bathroomType(),
                    floors.stream().map(WingFloorAggregate::floor).toList(),
                    // Every floor in a wing has the same room count in this building, so the first
                    // is representative. Taking it from real data rather than a hardcoded constant
                    // means the number stays right if the data ever changes.
                    first.roomCount().intValue(),
                    totalRooms,
                    totalBeds));
        });

        return summaries;
    }

    /**
     * One floor of one wing, ready to draw.
     *
     * @throws DomainException NOT_FOUND if the wing/floor combination does not exist - e.g. wing A
     *         has no basement, so /api/wings/A/floors/BAS/rooms is a genuine 404 rather than an
     *         empty map that looks like a rendering bug
     */
    public FloorRoomsDto getFloor(String wing, String floor) {
        String normalisedWing = wing == null ? "" : wing.trim().toUpperCase();
        String normalisedFloor = floor == null ? "" : floor.trim().toUpperCase();

        List<Room> rooms = roomRepository.findFloorWithBeds(normalisedWing, normalisedFloor);
        if (rooms.isEmpty()) {
            throw new DomainException(ErrorCode.NOT_FOUND,
                    "No floor '%s' in wing '%s'.".formatted(normalisedFloor, normalisedWing),
                    Map.of("wing", normalisedWing, "floor", normalisedFloor));
        }

        // One query for the whole floor's statuses, then map lookups per bed.
        Map<Long, BedStatus> statuses =
                bedStatusRepository.statusesForFloor(normalisedWing, normalisedFloor);

        int roomsOnFloor = rooms.size();
        GridDto grid = FloorGridLayout.gridFor(roomsOnFloor);

        List<RoomCellDto> cells = new ArrayList<>(roomsOnFloor);
        for (Room room : rooms) {
            List<BedStateDto> beds = room.getBeds().stream()
                    .map(bed -> new BedStateDto(
                            bed.getId(),
                            bed.getBedLabel(),
                            // Default AVAILABLE if the view somehow has no row for this bed. It
                            // cannot happen (the view LEFT JOINs from beds), but defaulting to the
                            // most restrictive-to-nobody value beats a null reaching the frontend.
                            statuses.getOrDefault(bed.getId(), BedStatus.AVAILABLE)))
                    .toList();

            cells.add(new RoomCellDto(
                    room.getId(),
                    room.getRoomNumber(),
                    room.getRoomType(),
                    room.getBathroomType(),
                    room.getCapacity(),
                    FloorGridLayout.cellFor(room.getPositionInFloor(), roomsOnFloor),
                    RoomStatus.rollUp(beds.stream().map(BedStateDto::status).toList()),
                    beds));
        }

        return new FloorRoomsDto(
                normalisedWing,
                normalisedFloor,
                rooms.get(0).getFloorLevel(),
                grid,
                cells);
    }
}
