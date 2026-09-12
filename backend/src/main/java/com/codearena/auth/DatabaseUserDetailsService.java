package com.codearena.auth;

import com.codearena.user.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads accounts for the authentication provider. Login accepts either the username or
 * the email address, since users reliably remember one or the other.
 */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public DatabaseUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {
        return userRepository.findByUsernameOrEmail(identifier)
                .map(AuthenticatedUser::from)
                // The message never reaches the client: DaoAuthenticationProvider
                // converts this into a BadCredentialsException so that a failed login
                // cannot distinguish "no such account" from "wrong password".
                .orElseThrow(() -> new UsernameNotFoundException("No account for the supplied identifier"));
    }
}
