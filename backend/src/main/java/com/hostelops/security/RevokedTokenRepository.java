package com.hostelops.security;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface RevokedTokenRepository extends JpaRepository<RevokedToken, UUID> {

    /**
     * Deletes rows for tokens that have expired anyway.
     *
     * <p>{@code @Modifying} tells Spring Data this query changes data rather than reading it -
     * without it, Spring tries to run a DELETE as if it were a SELECT and fails. Deleting in a
     * single statement also avoids loading every row into memory just to delete it.
     *
     * @return how many rows were removed
     */
    @Modifying
    @Query("DELETE FROM RevokedToken r WHERE r.expiresAt < :now")
    int deleteExpiredBefore(@Param("now") Instant now);
}
