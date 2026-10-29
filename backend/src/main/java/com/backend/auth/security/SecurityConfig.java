package com.backend.auth.security;

import com.backend.auth.service.AuthService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    static final String[] OPEN_PATHS = {
            "/api/auth/account",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/device/code",
            "/api/auth/device/token",
            "/api/auth/device/qr.svg",
            "/api/auth/device/pending",
            "/api/auth/device/claim-invite",
            "/api/sessions/join/preview",
            "/api/version",
            "/api/admin/login"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public BearerTokenFilter bearerTokenFilter(AuthService auth, Ed25519Verifier verifier) {
        return new BearerTokenFilter(auth);
    }

    @Bean
    public com.backend.admin.security.AdminAuthFilter adminAuthFilter(com.backend.admin.service.AdminAuthService adminAuthService) {
        return new com.backend.admin.security.AdminAuthFilter(adminAuthService);
    }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, BearerTokenFilter bearerTokenFilter,
                                           com.backend.admin.security.AdminAuthFilter adminAuthFilter)
            throws Exception {
        http
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers(HttpMethod.GET, "/api/items/*/blob").permitAll()
                        .requestMatchers(OPEN_PATHS).permitAll()
                        .requestMatchers("/admin", "/admin.html", "/admin.css", "/admin.js").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("ROLE_ADMIN")
                        .requestMatchers("/ws", "/ws/signal").authenticated()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll())

                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; connect-src 'self' wss: ws: https:; object-src 'none'; frame-ancestors 'none';"))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(new JsonAuthenticationEntryPoint()))
                .addFilterBefore(adminAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(bearerTokenFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
