package com.backend.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless bearer-token security.
 *
 * <p>Off: sessions, CSRF tokens, form login, HTTP Basic — none of which have anything to do with
 * an app that proves itself with a token on every call, and all of which would only get in the way.
 *
 * <p>Open: creating an account, the steps of pairing (asking for a code, polling for a token,
 * drawing and describing the QR), and the web app's own files. Everything else under {@code /api}
 * needs a token, and so does the websocket handshake — a browser passes it as {@code /ws?token=…}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The only endpoints that answer to a stranger. Everything here is a step of pairing or the one
     * call that creates an account; the last two only answer with what a code already says about
     * itself, so knowing the code is knowing everything they know.
     */
    static final String[] OPEN_PATHS = {
            "/api/auth/account",
            "/api/auth/device/code",
            "/api/auth/device/token",
            "/api/auth/device/qr.svg",
            "/api/auth/device/pending"
    };

    @Bean
    public BearerTokenFilter bearerTokenFilter(AuthService auth) {
        return new BearerTokenFilter(auth);
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, BearerTokenFilter bearerTokenFilter)
            throws Exception {
        http
                .authorizeHttpRequests(rules -> rules
                        // blob bytes are reachable by a signed link too, so that <img> and plain
                        // links work; the endpoint still checks the signature or the owner's token
                        .requestMatchers(HttpMethod.GET, "/api/items/*/blob").permitAll()
                        .requestMatchers(OPEN_PATHS).permitAll()
                        .requestMatchers("/ws").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        // the web app itself: index.html, app.js, style.css, the icons
                        .anyRequest().permitAll())

                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(anonymous -> anonymous.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
