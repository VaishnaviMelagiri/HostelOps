package com.hostelops.security;

import com.hostelops.user.Role;
import com.hostelops.user.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Adapts our {@link User} entity to Spring Security's {@link UserDetails} interface - the shape
 * Spring expects whenever it asks "who is this and what may they do?".
 *
 * <p>The interesting method is {@link #getAuthorities()}. Spring Security has no concept of "roles"
 * beyond authorities that happen to start with {@code ROLE_}; it only compares authority strings.
 * We map each {@link Permission} to one authority, and grant no {@code ROLE_*} authority at all.
 * That is what makes {@code hasRole('ADMIN')} impossible to write by accident anywhere in this
 * codebase - the authority simply does not exist, so such a check would always be false and would
 * be caught the first time it ran.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Role role;
    private final String fullName;
    private final boolean active;
    private final List<GrantedAuthority> authorities;

    public AppUserPrincipal(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.passwordHash = user.getPasswordHash();
        this.role = user.getRole();
        this.fullName = user.getFullName();
        this.active = user.isActive();
        this.authorities = RolePermissions.forRole(user.getRole()).stream()
                .map(permission -> (GrantedAuthority) new SimpleGrantedAuthority(permission.name()))
                .toList();
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    /**
     * Spring Security calls the login identifier a "username"; ours is an email address. The name
     * is the framework's, the meaning is ours.
     */
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /**
     * A deactivated user is refused even while holding a valid, unexpired token - because the user
     * is re-read from the database on every request rather than trusted from the token's claims.
     */
    @Override
    public boolean isEnabled() {
        return active;
    }
}
