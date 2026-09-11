package net.citotech.cito.config;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.citotech.cito.platform.CitoMerchantFeatureAuthorizationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    static final List<String> CSRF_EXEMPT_API_PATTERNS =
            List.of(
                    "/api/v1/**",
                    "/api/do*",
                    "/api/test*",
                    "/api/v2/admin/**",
                    "/api/v2/balances",
                    "/api/v2/channels",
                    "/api/v2/health",
                    "/api/v2/native/**",
                    "/api/v2/provider-callbacks/**",
                    "/api/v2/payments/**",
                    "/api/v2/production-maturity/**",
                    "/api/v2/vending/**",
                    "/api/public/analytics/events",
                    "/api/public/sales-enquiries",
                    "/actuator/**",
                    "/status/**");

    static final List<String> ADMIN_API_PATTERNS =
            List.of(
                    "/api/v2/admin/**",
                    "/api/v2/production-maturity/**",
                    "/api/v2/product-experience/**",
                    "/api/v2/cross-border/**",
                    "/api/v2/beneficiaries/**",
                    "/api/v2/fx/**");

    static final List<String> PUBLIC_ANONYMOUS_API_PATTERNS =
            List.of(
                    "/api/public/embedded/onboarding/**",
                    "/api/public/analytics/events",
                    "/api/public/sales-enquiries",
                    "/api/public/status");

    static final List<String> PUBLIC_SIGNED_API_PATTERNS =
            List.of(
                    "/api/v1/**",
                    "/api/do*",
                    "/api/test*",
                    "/api/v2/health",
                    "/api/v2/balances",
                    "/api/v2/channels",
                    "/api/v2/payments/**",
                    "/api/v2/native/**",
                    "/api/v2/provider-callbacks/**",
                    "/api/v2/refunds/**",
                    "/api/v2/batch-payouts/**",
                    "/api/v2/accounts/**",
                    "/api/v2/statements",
                    "/api/v2/payment-links",
                    "/api/v2/invoices/**",
                    "/api/v2/fees/**",
                    "/api/v2/webhooks/events",
                    "/api/v2/vending/**");

    static final List<String> PUBLIC_SESSION_API_PATTERNS =
            List.of(
                    "/api/v2/session/me",
                    "/api/v2/merchant/**",
                    "/api/v2/merchant-self-service/**",
                    "/api/v2/portal/**",
                    "/api/v2/merchants/**",
                    "/api/v2/transactions/**",
                    "/api/v2/support/**",
                    "/api/v2/search",
                    "/api/v2/notifications/**",
                    "/api/v2/provider-incidents/**");

    static final List<String> PUBLIC_PAGE_AND_LEGACY_PORTAL_PATTERNS =
            List.of(
                    "/",
                    "/dashboard",
                    "/dashboardMerchant",
                    "/portal",
                    "/checkout/**",
                    "/vending/rent/**",
                    "/auth/**",
                    "/admins/**",
                    "/audittrail/**",
                    "/merchants/**",
                    "/settings/**",
                    "/status/**",
                    "/transactions/**");

    @Value(
            "${cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000,http://[::1]:3000,http://localhost:2019,http://127.0.0.1:2019,http://[::1]:2019}")
    private String[] allowedOrigins;

    @Value("${actuator.username}")
    private String actuatorUsername;

    @Value("${actuator.password}")
    private String actuatorPassword;

    @Value("${admin.api.username}")
    private String adminUsername;

    @Value("${admin.api.password}")
    private String adminPassword;

    @Value("${csp.connect-src.extra:http://localhost:8081 http://127.0.0.1:8081}")
    private String cspConnectSrcExtra;

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            LegacySessionAuthorizationFilter legacySessionAuthorizationFilter,
            CitoMerchantFeatureAuthorizationFilter citoMerchantFeatureAuthorizationFilter)
            throws Exception {
        CsrfTokenRequestAttributeHandler csrfRequestHandler =
                new CsrfTokenRequestAttributeHandler();

        http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(
                        csrf ->
                                csrf.csrfTokenRepository(
                                                CookieCsrfTokenRepository.withHttpOnlyFalse())
                                        .csrfTokenRequestHandler(csrfRequestHandler)
                                        .ignoringRequestMatchers(
                                                request ->
                                                        !request.getRequestURI()
                                                                        .startsWith(
                                                                                "/api/v2/admin/api-reference")
                                                                && CSRF_EXEMPT_API_PATTERNS.stream()
                                                                        .anyMatch(
                                                                                pattern ->
                                                                                        new org
                                                                                                        .springframework
                                                                                                        .util
                                                                                                        .AntPathMatcher()
                                                                                                .match(
                                                                                                        pattern,
                                                                                                        request
                                                                                                                .getRequestURI()))))
                .headers(
                        headers ->
                                headers.contentTypeOptions(contentType -> {})
                                        .frameOptions(frame -> frame.sameOrigin())
                                        .referrerPolicy(
                                                referrer ->
                                                        referrer.policy(
                                                                ReferrerPolicyHeaderWriter
                                                                        .ReferrerPolicy
                                                                        .SAME_ORIGIN))
                                        .httpStrictTransportSecurity(
                                                hsts ->
                                                        hsts.includeSubDomains(true)
                                                                .preload(true)
                                                                .maxAgeInSeconds(31536000))
                                        .contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'self'; "
                                                                        + "script-src 'self'; "
                                                                        + "style-src 'self' 'unsafe-inline'; "
                                                                        + "img-src 'self' data: https:; "
                                                                        + "font-src 'self' data:; "
                                                                        + "connect-src 'self'"
                                                                        + connectSrcExtra()
                                                                        + "; "
                                                                        + "object-src 'none'; "
                                                                        + "frame-ancestors 'self'; "
                                                                        + "base-uri 'self'; "
                                                                        + "form-action 'self'"))
                                        .addHeaderWriter(
                                                new StaticHeadersWriter(
                                                        "Permissions-Policy",
                                                        "camera=(), microphone=(), geolocation=(), usb=(), payment=(self)"))
                                        .addHeaderWriter(
                                                new StaticHeadersWriter(
                                                        "Cross-Origin-Opener-Policy",
                                                        "same-origin"))
                                        .addHeaderWriter(
                                                new StaticHeadersWriter(
                                                        "Cross-Origin-Resource-Policy",
                                                        "same-origin"))
                                        .addHeaderWriter(
                                                new StaticHeadersWriter(
                                                        "X-Permitted-Cross-Domain-Policies",
                                                        "none")))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .requestMatchers(
                                                "/v3/api-docs",
                                                "/v3/api-docs/**",
                                                "/v3/api-docs.yaml")
                                        .hasRole("ADMIN")
                                        .requestMatchers(ADMIN_API_PATTERNS.toArray(String[]::new))
                                        .hasRole("ADMIN")
                                        .requestMatchers("/actuator/**")
                                        .hasRole("ACTUATOR")
                                        .requestMatchers(
                                                PUBLIC_ANONYMOUS_API_PATTERNS.toArray(
                                                        String[]::new))
                                        .permitAll()
                                        .requestMatchers(
                                                PUBLIC_SIGNED_API_PATTERNS.toArray(String[]::new))
                                        .permitAll()
                                        .requestMatchers(
                                                PUBLIC_SESSION_API_PATTERNS.toArray(String[]::new))
                                        .permitAll()
                                        .requestMatchers(
                                                PUBLIC_PAGE_AND_LEGACY_PORTAL_PATTERNS.toArray(
                                                        String[]::new))
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .sessionManagement(
                        session ->
                                session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                                        .sessionFixation(fixation -> fixation.migrateSession()))
                .addFilterBefore(legacySessionAuthorizationFilter, AuthorizationFilter.class)
                .addFilterAfter(
                        citoMerchantFeatureAuthorizationFilter,
                        LegacySessionAuthorizationFilter.class)
                .httpBasic(httpBasic -> {});
        return http.build();
    }

    /**
     * The feature entitlement filter belongs to the Spring Security chain only. Disabling servlet
     * auto-registration makes its order deterministic and avoids the same filter executing outside
     * the security chain before session/authentication bridging has run.
     */
    @Bean
    public FilterRegistrationBean<CitoMerchantFeatureAuthorizationFilter>
            citoMerchantFeatureAuthorizationFilterRegistration(
                    CitoMerchantFeatureAuthorizationFilter filter) {
        FilterRegistrationBean<CitoMerchantFeatureAuthorizationFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        validateCredentials(
                actuatorUsername,
                actuatorPassword,
                "ACTUATOR_USERNAME and ACTUATOR_PASSWORD must be set");
        validateCredentials(
                adminUsername,
                adminPassword,
                "ADMIN_API_USERNAME and ADMIN_API_PASSWORD must be set");

        if (actuatorUsername.equalsIgnoreCase(adminUsername)) {
            UserDetails combinedUser =
                    User.withUsername(actuatorUsername)
                            .password(encoder.encode(actuatorPassword))
                            .roles("ACTUATOR", "ADMIN")
                            .build();
            return new InMemoryUserDetailsManager(combinedUser);
        }

        UserDetails actuatorUser =
                User.withUsername(actuatorUsername)
                        .password(encoder.encode(actuatorPassword))
                        .roles("ACTUATOR")
                        .build();
        UserDetails adminUser =
                User.withUsername(adminUsername)
                        .password(encoder.encode(adminPassword))
                        .roles("ADMIN")
                        .build();
        return new InMemoryUserDetailsManager(actuatorUser, adminUser);
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration trustedConfig = new CorsConfiguration();
        trustedConfig.setAllowedOrigins(expandLoopbackAliases(allowedOrigins));
        trustedConfig.setAllowedMethods(
                Arrays.asList("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        trustedConfig.setAllowedHeaders(
                List.of(
                        "Authorization",
                        "Content-Type",
                        "X-Cito-Api-Key",
                        "X-Cito-Environment",
                        "X-CSRF-TOKEN",
                        "X-CPay-Merchant",
                        "X-CPay-Signature",
                        "X-CPay-Timestamp",
                        "X-CPay-Nonce",
                        "X-CPay-Environment",
                        "X-CPay-Idempotency-Key",
                        "X-Idempotency-Key",
                        "Idempotency-Key",
                        "X-Request-ID"));
        trustedConfig.setExposedHeaders(List.of("X-Request-ID", "Deprecation", "Sunset", "Link"));
        trustedConfig.setAllowCredentials(true);
        trustedConfig.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", trustedConfig);
        source.registerCorsConfiguration("/**", trustedConfig);
        return source;
    }

    private List<String> expandLoopbackAliases(String[] origins) {
        Set<String> expandedOrigins = new LinkedHashSet<>();
        for (String origin : origins) {
            if (isBlank(origin)) {
                continue;
            }
            String trimmedOrigin = origin.trim();
            expandedOrigins.add(trimmedOrigin);
            addLoopbackAlias(expandedOrigins, trimmedOrigin, "localhost");
            addLoopbackAlias(expandedOrigins, trimmedOrigin, "127.0.0.1");
            addLoopbackAlias(expandedOrigins, trimmedOrigin, "[::1]");
        }
        return List.copyOf(expandedOrigins);
    }

    private void addLoopbackAlias(Set<String> origins, String origin, String aliasHost) {
        try {
            URI uri = URI.create(origin);
            String host = uri.getHost();
            if (!isLoopbackHost(host)) {
                return;
            }
            String scheme = uri.getScheme();
            int port = uri.getPort();
            if (isBlank(scheme) || port < 0) {
                return;
            }
            origins.add(scheme + "://" + aliasHost + ":" + port);
        } catch (IllegalArgumentException ignored) {
            // Leave any non-URI origin unchanged; Spring will validate it later.
        }
    }

    private boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private void validateCredentials(String username, String password, String message) {
        if (isBlank(username) || isBlank(password)) {
            throw new IllegalStateException(message);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private String connectSrcExtra() {
        return isBlank(cspConnectSrcExtra) ? "" : " " + cspConnectSrcExtra.trim();
    }
}
