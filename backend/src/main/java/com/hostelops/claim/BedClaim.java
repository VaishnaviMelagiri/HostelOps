package com.hostelops.claim;

import com.hostelops.room.Bed;
import com.hostelops.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * A claim on a bed - either a student's request/allocation, or an admin's maintenance block.
 *
 * <p>One table holds both on purpose. It is what lets ONE unique index
 * ({@code uq_claim_live_per_bed}) cover the student-versus-student race AND the
 * admin-blocks-while-a-student-requests race. Two separate tables cannot share an index, so that
 * second race would need a row lock instead.
 *
 * <p>A block has {@code student_id IS NULL}; a student claim never does. The database enforces that
 * with {@code ck_claims_owner}, so the two kinds of row cannot be confused even by a bug.
 */
@Entity
@Table(name = "bed_claims")
public class BedClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bed_id", nullable = false)
    private Bed bed;

    /** NULL exactly when this row is a maintenance block. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id")
    private User student;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ClaimStatus status;

    /** Who created the row: the student for a request, the admin for a block. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /**
     * Filled by the column's {@code DEFAULT now()}, not by Java, so every row is stamped by the
     * database clock rather than by whichever server happened to handle the request.
     *
     * <p>{@code @Generated(event = INSERT)} tells Hibernate to read the value back immediately
     * after the INSERT. Without it the field stays null in memory and any DTO built from this
     * object right after saving would report a null creation time.
     */
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    @Generated(event = EventType.INSERT)
    private Instant createdAt;

    /** Set only while PENDING. Enforced by ck_claims_ttl. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decision_reason")
    private String decisionReason;

    /** The client's Idempotency-Key, so one click cannot become two rows. */
    @Column(name = "request_key")
    private UUID requestKey;

    /**
     * Optimistic locking version.
     *
     * <p>{@code @Version} makes Hibernate add {@code AND version = ?} to every UPDATE and bump the
     * number. If another transaction changed the row first, zero rows match and Hibernate throws
     * rather than silently overwriting. Unused in Phase 4 (requests are inserts), but Phase 5's
     * approve/reject are updates, and the column has to exist before then.
     */
    @Version
    private Integer version;

    protected BedClaim() {
        // Required by JPA.
    }

    /** A student's request for a bed. Always starts PENDING with a deadline. */
    public static BedClaim pendingRequest(Bed bed, User student, Instant expiresAt, UUID requestKey) {
        BedClaim claim = new BedClaim();
        claim.bed = bed;
        claim.student = student;
        claim.createdBy = student;
        claim.status = ClaimStatus.PENDING;
        claim.expiresAt = expiresAt;
        claim.requestKey = requestKey;
        return claim;
    }

    /**
     * An admin taking a bed out of circulation.
     *
     * <p>No student, and no decidedAt/decidedBy: a block is a row BORN in its state, so the actor
     * is recorded in createdBy. decidedBy is filled only when someone later lifts it. The database
     * enforces exactly that shape through ck_claims_owner and ck_claims_decided.
     */
    public static BedClaim maintenanceBlock(Bed bed, User admin, String reason) {
        BedClaim claim = new BedClaim();
        claim.bed = bed;
        claim.student = null;
        claim.createdBy = admin;
        claim.status = ClaimStatus.BLOCKED;
        claim.expiresAt = null;
        claim.decisionReason = reason;
        return claim;
    }

    public Long getId() {
        return id;
    }

    public Bed getBed() {
        return bed;
    }

    public User getStudent() {
        return student;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public UUID getRequestKey() {
        return requestKey;
    }
}
