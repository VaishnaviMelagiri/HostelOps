package com.hostelops.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * A person who can sign in. Maps to the {@code users} table created by V1__core_tables.sql.
 *
 * <p>This is the first JPA entity in the project, so a few Spring/JPA concepts appear at once:
 * <ul>
 *   <li>{@code @Entity} - Hibernate manages this class and maps it to a table.</li>
 *   <li>{@code @Table(name = "users")} - needed because {@code USER} is a reserved word in SQL;
 *       without it Hibernate would look for a table named {@code user} and Postgres would object.</li>
 *   <li>{@code @GeneratedValue(strategy = IDENTITY)} - the database assigns the id
 *       ({@code GENERATED ALWAYS AS IDENTITY}), and Hibernate reads it back after the insert.</li>
 *   <li>{@code @Enumerated(EnumType.STRING)} - store {@code "STUDENT"}, not {@code 0}. Ordinal
 *       storage is a well-known trap: reordering the enum silently reassigns every existing row.</li>
 * </ul>
 *
 * <p>Because {@code spring.jpa.hibernate.ddl-auto} is {@code validate}, every field below must match
 * the migration exactly. If they disagree, the application refuses to start with a precise message
 * instead of failing later on a real query.
 *
 * <p>JPA requires a no-argument constructor and non-final fields, which is why this is a plain class
 * rather than a record like the DTOs.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Role role;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /** Null for ADMIN and GUEST - enforced by ck_users_student_fields. */
    @Column(name = "student_code", length = 20)
    private String studentCode;

    /** Null for ADMIN and GUEST. Visible to a confirmed roommate only (Phase 8). */
    @Column(length = 80)
    private String course;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected User() {
        // Required by JPA. Not for application code - use the factory methods below.
    }

    private User(String email, String passwordHash, Role role,
                 String fullName, String studentCode, String course) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.fullName = fullName;
        this.studentCode = studentCode;
        this.course = course;
        this.active = true;
    }

    /** A student always has a code and a course - the database will reject one without them. */
    public static User student(String email, String passwordHash, String fullName,
                               String studentCode, String course) {
        return new User(email, passwordHash, Role.STUDENT, fullName, studentCode, course);
    }

    /** Staff. No student code, no course. */
    public static User admin(String email, String passwordHash, String fullName) {
        return new User(email, passwordHash, Role.ADMIN, fullName, null, null);
    }

    /** Read-only reviewer. Exempt from the student-fields rule for the same reason ADMIN is. */
    public static User guest(String email, String passwordHash, String fullName) {
        return new User(email, passwordHash, Role.GUEST, fullName, null, null);
    }

    public Long getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public String getFullName() {
        return fullName;
    }

    public String getStudentCode() {
        return studentCode;
    }

    public String getCourse() {
        return course;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
