package com.hostelops.security;

import com.hostelops.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells Spring Security how to find a user.
 *
 * <p>{@link UserDetailsService} is a one-method interface Spring calls during password
 * authentication. Because we expose it as a bean, Spring Boot wires it into the authentication
 * machinery automatically - and, importantly, stops generating the random development password it
 * prints at startup when no user source exists.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /**
     * @param email the login identifier (Spring's interface calls it a username)
     * @throws UsernameNotFoundException if no such user exists. Note that the caller must NOT
     *         surface this distinction: telling an anonymous caller "no such account" versus "wrong
     *         password" hands them a way to discover which email addresses are registered. See
     *         {@link com.hostelops.auth.AuthService}, which answers INVALID_CREDENTIALS to both.
     */
    @Override
    @Transactional(readOnly = true)
    public AppUserPrincipal loadUserByUsername(String email) {
        return userRepository.findByEmailIgnoreCase(email)
                .map(AppUserPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("No user with email " + email));
    }
}
