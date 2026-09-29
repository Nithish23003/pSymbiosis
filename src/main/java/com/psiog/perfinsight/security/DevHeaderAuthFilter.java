package com.psiog.perfinsight.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * DEV ONLY (profile "dev"): authenticate with headers
 *   X-Dev-User: priya.sharma@psiog.com
 *   X-Dev-Roles: MANAGER
 * so the API can be exercised before the Azure AD app registration is ready.
 */
public class DevHeaderAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String user = req.getHeader("X-Dev-User");
        if (user != null && !user.isBlank() && req.getHeader("Authorization") == null) {
            String rolesHeader = req.getHeader("X-Dev-Roles");
            List<SimpleGrantedAuthority> auths = Arrays.stream((rolesHeader == null ? "EMPLOYEE" : rolesHeader).split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> new SimpleGrantedAuthority("ROLE_" + s.toUpperCase(Locale.ROOT)))
                    .toList();
            var token = new UsernamePasswordAuthenticationToken(user.toLowerCase(Locale.ROOT), "n/a", auths);
            SecurityContextHolder.getContext().setAuthentication(token);
        }
        chain.doFilter(req, res);
    }
}
