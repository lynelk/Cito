package net.citotech.cito.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.citotech.cito.GeneralException;
import net.citotech.cito.Model.User;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LegacySessionAuthorizationFilter extends OncePerRequestFilter {
    private static final List<String> PORTAL_SESSION_PREFIXES =
            List.of(
                    "/admins",
                    "/audittrail",
                    "/merchants",
                    "/settings",
                    "/transactions",
                    "/api/v2/admin/provider-treasury",
                    "/api/v2/admin/shared-provider",
                    "/api/v2/merchant-self-service/channels",
                    "/api/v2/merchant-self-service/batches",
                    "/api/v2/merchant-self-service/webhooks",
                    "/api/v2/merchant-self-service/vending",
                    "/api/v2/portal",
                    "/api/v2/merchants",
                    "/api/v2/transactions",
                    "/api/v2/support",
                    "/api/v2/search",
                    "/api/v2/notifications",
                    "/api/v2/provider-incidents");
    private static final List<String> PUBLIC_SETTINGS_PATHS =
            List.of("/settings/public-login-appearance");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        bridgeAdminSessionIdentity(session);
        if (request.getRequestURI().startsWith("/v3/api-docs")) {
            response.setHeader("Cache-Control", "private, no-store");
            if (session == null || !(session.getAttribute("user") instanceof User)) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                return;
            }
        }

        if (!requiresPortalSession(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        boolean loggedIn =
                session != null
                        && (session.getAttribute("user") != null
                                || session.getAttribute("merchantUser") != null);
        loggedIn = loggedIn || hasAdministratorAuthentication();
        if (loggedIn) {
            filterChain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(GeneralException.getError("107", GeneralException.ERRORS_107));
    }

    private boolean hasAdministratorAuthentication() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null
                && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    /**
     * The legacy admin login stores the real operator as session attribute "user", while Spring
     * Security historically knew only the shared ADMIN_API basic-auth principal. Bridge the real
     * session identity into the SecurityContext so /api/v2/admin/** authorizes the same signed-in
     * operator and maker-checker/audit code receives a human identity rather than a shared service
     * username. Merchant sessions are deliberately never promoted to ROLE_ADMIN. Browser-facing
     * provider administration routes are included in {@link #PORTAL_SESSION_PREFIXES} so a missing
     * portal session receives Cito's JSON 401 response instead of triggering the browser's native
     * HTTP Basic credential dialog. A pre-authenticated ADMIN_API request still passes this gate.
     */
    private void bridgeAdminSessionIdentity(HttpSession session) {
        if (session == null || !(session.getAttribute("user") instanceof User user)) {
            return;
        }
        String principal = adminPrincipal(user);
        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        authentication.setDetails("legacy-admin-session:" + user.getId());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String adminPrincipal(User user) {
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return user.getEmail().trim();
        }
        if (user.getName() != null && !user.getName().isBlank()) {
            return user.getName().trim() + "#" + user.getId();
        }
        return "admin-user-" + user.getId();
    }

    boolean requiresPortalSession(HttpServletRequest request) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        if (HttpMethod.GET.matches(request.getMethod()) && PUBLIC_SETTINGS_PATHS.contains(path)) {
            return false;
        }
        return PORTAL_SESSION_PREFIXES.stream().anyMatch(path::startsWith);
    }
}
