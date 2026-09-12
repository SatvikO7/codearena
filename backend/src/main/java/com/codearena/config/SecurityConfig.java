package com.codearena.config;

import com.codearena.auth.SecurityErrorResponder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.Map;

/**
 * Authentication and authorisation policy.
 *
 * <h2>Why sessions rather than JWT</h2>
 * The client is a browser and there is exactly one API server; the judge worker never
 * authenticates a user. In that setting a stateless token buys nothing and costs the one
 * property this system actually needs: the ability to revoke access immediately. Logging
 * out, or disabling an abusive account, must take effect now, not whenever a token
 * happens to expire. Doing that with JWT means a server-side denylist checked on every
 * request, which is a session store with extra steps. Sessions live in Redis, so the API
 * server can restart or scale horizontally without signing anyone out. The trade-off is
 * recorded in docs/decisions.md (ADR-010).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Paths reachable without authentication. */
    private static final String[] PUBLIC_PATHS = {
            "/api/system/info",
            "/actuator/health",
            "/actuator/health/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    private final SecurityErrorResponder securityErrorResponder;
    private final boolean requireSecureCookies;

    public SecurityConfig(SecurityErrorResponder securityErrorResponder,
                          @Value("${codearena.security.require-secure-cookies:false}") boolean requireSecureCookies) {
        this.securityErrorResponder = securityErrorResponder;
        this.requireSecureCookies = requireSecureCookies;
    }

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      SecurityContextRepository securityContextRepository)
            throws Exception {

        // CSRF applies because the browser attaches the session cookie automatically. The
        // token is delivered in a JavaScript-readable cookie and echoed back in a header
        // that a browser will not attach cross-site, which is the standard SPA arrangement.
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // Configured through the cookie customizer; the individual setSecure/setCookiePath
        // setters are deprecated in current Spring Security.
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .secure(requireSecureCookies)
                .sameSite("Lax"));

        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        // Resolve the token on every request instead of lazily. Without this the cookie is
        // only issued once something reads the token, and the first POST a freshly loaded
        // SPA makes would have no token to send.
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf
                    .csrfTokenRepository(csrfTokenRepository)
                    .csrfTokenRequestHandler(csrfRequestHandler))
            .securityContext(context -> context
                    .securityContextRepository(securityContextRepository)
                    .requireExplicitSave(true))
            .sessionManagement(session -> session
                    .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            // Logout is handled by AuthController so that all four authentication
            // endpoints are defined, documented and tested in one place. Spring's own
            // logout filter is switched off to avoid a second, undocumented /logout.
            .logout(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .exceptionHandling(handling -> handling
                    .authenticationEntryPoint(securityErrorResponder)
                    .accessDeniedHandler(securityErrorResponder))
            .headers(headers -> headers
                    .frameOptions(frame -> frame.deny())
                    .contentTypeOptions(Customizer.withDefaults()))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(PUBLIC_PATHS).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults()
                            .matcher(org.springframework.http.HttpMethod.POST, "/api/auth/register")).permitAll()
                    .requestMatchers(PathPatternRequestMatcher.withDefaults()
                            .matcher(org.springframework.http.HttpMethod.POST, "/api/auth/login")).permitAll()
                    // Reserved for the admin surface built in later phases. The rule
                    // exists now so authorisation is enforced the day the first admin
                    // endpoint appears, rather than being remembered afterwards.
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    .anyRequest().authenticated());

        return http.build();
    }

    /**
     * Sessions are the only credential store, so the security context is persisted into
     * the HTTP session, which Spring Session then keeps in Redis.
     */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Report a missing account as bad credentials so a failed login cannot be used to
        // discover which usernames exist.
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    /**
     * BCrypt at cost 12, wrapped in a delegating encoder.
     *
     * <p>Stored hashes carry their algorithm as a prefix, so a future move to Argon2 is a
     * change of default here plus a re-hash on next login, while existing hashes keep
     * verifying throughout. A bare BCryptPasswordEncoder would make that migration a
     * breaking change.
     *
     * <p>Cost 12 is roughly a quarter-second per verification: expensive enough to make
     * offline cracking painful, and precisely why login must be rate limited before this
     * is exposed to the internet (Phase 9).
     */
    @Bean
    public PasswordEncoder passwordEncoder(
            @Value("${codearena.security.bcrypt-strength:12}") int bcryptStrength) {
        String encodingId = "bcrypt";
        Map<String, PasswordEncoder> encoders = Map.of(
                encodingId, new BCryptPasswordEncoder(bcryptStrength));
        return new DelegatingPasswordEncoder(encodingId, encoders);
    }
}
