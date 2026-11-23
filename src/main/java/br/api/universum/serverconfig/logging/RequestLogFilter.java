package br.api.universum.serverconfig.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registra uma linha por requisição: método, caminho, status, duração e origem.
 * Roda antes do Spring Security, então também registra requisições recusadas (401/403).
 * Não registra headers de autenticação, query string nem corpo da resposta, porque as
 * configurações servidas podem conter segredos.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "universum.request-log", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RequestLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("request");

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            log.info(
                    "method={} path={} status={} durationMs={} remote={} xForwardedFor={} host={} userAgent=\"{}\"",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs,
                    request.getRemoteAddr(),
                    orDash(request.getHeader("X-Forwarded-For")),
                    orDash(request.getHeader("Host")),
                    orDash(request.getHeader("User-Agent"))
            );
        }
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
