package net.citotech.cito.api.v2;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Finalizes acquired claims even when a controller converts a submission exception to HTTP. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdempotencyRequestFilter extends OncePerRequestFilter {
    private final IdempotencyService idempotency;

    public IdempotencyRequestFilter(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (var scope = idempotency.openRequestScope()) {
            chain.doFilter(request, response);
        }
    }
}
