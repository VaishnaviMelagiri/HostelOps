package com.hostelops.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Data access for {@link User}.
 *
 * <p>Spring Data JPA writes the implementation at startup: we declare an interface, and Spring
 * generates a bean that implements it. Extending {@code JpaRepository<User, Long>} already supplies
 * {@code findById}, {@code save}, {@code count} and friends without a line of code.
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Look up a sign-in by email, case-insensitively.
     *
     * <p>Written as an explicit {@code @Query} using {@code lower(...)} on both sides so it matches
     * the functional unique index {@code uq_users_email ON users (lower(email))}. That is not a
     * detail: a query on raw {@code email} could not use that index, and - worse - it would let
     * {@code Admin@hostelops.demo} fail to sign in even though the row exists. The uniqueness rule
     * and the lookup have to agree on what "the same email" means.
     */
    @Query("SELECT u FROM User u WHERE lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    boolean existsByRole(Role role);
}
