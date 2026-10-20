package com.wechat.jxsc.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.jxsc.common.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

public class BearerSessionFilter extends OncePerRequestFilter {
    private final SessionService sessions;
    private final ObjectMapper json;

    public BearerSessionFilter(SessionService sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null) {
            try {
                if (!header.startsWith("Bearer ")
                        || java.util.Collections.list(request.getHeaders("Authorization")).size()
                                != 1) throw CurrentActor.unauthorized();
                var actor = sessions.authenticate(header.substring(7));
                var authentication =
                        new UsernamePasswordAuthenticationToken(
                                actor,
                                null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + actor.type().name())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
                if (actor.passwordChangeRequired() && !allowedDuringPasswordChange(request))
                    throw new ApiException(
                            org.springframework.http.HttpStatus.FORBIDDEN,
                            "FORBIDDEN",
                            "Password change required");
            } catch (ApiException exception) {
                SecurityContextHolder.clearContext();
                response.setStatus(exception.getStatus().value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                json.writeValue(
                        response.getOutputStream(),
                        new ApiResponse<>(
                                exception.getCode(),
                                exception.getMessage(),
                                null,
                                RequestIdFilter.requestId(request)));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean allowedDuringPasswordChange(HttpServletRequest request) {
        return ("GET".equals(request.getMethod())
                        && "/api/v1/admin/me".equals(request.getRequestURI()))
                || ("POST".equals(request.getMethod())
                        && ("/api/v1/admin/auth/change-password".equals(request.getRequestURI())
                                || "/api/v1/admin/auth/logout".equals(request.getRequestURI())));
    }
}
