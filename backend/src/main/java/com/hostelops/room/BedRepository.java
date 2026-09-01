package com.hostelops.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BedRepository extends JpaRepository<Bed, Long> {

    /** Loads a bed together with its room, so a DTO can name the room without a second query. */
    @Query("SELECT b FROM Bed b JOIN FETCH b.room WHERE b.id = :id")
    Optional<Bed> findByIdWithRoom(@Param("id") Long id);
}
