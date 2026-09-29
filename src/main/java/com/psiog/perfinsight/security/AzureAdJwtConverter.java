package com.psiog.perfinsight.security;

import com.psiog.perfinsight.config.AppProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.*;

/** Maps the Azure AD "roles" claim (app roles) to Spring authorities ROLE_ADMIN, ROLE_MANAGER, ... */
@Component
@RequiredArgsConstructor
public class AzureAdJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final AppProperties props;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null) {
            Map<String, String> mapping = props.getSecurity().getRoleMapping();
            for (String r : roles) {
                String internal = mapping.getOrDefault(r, null);
                if (internal == null) {
                    // tolerate values that already match internal names
                    try { internal = AppRole.valueOf(r.toUpperCase(Locale.ROOT)).name(); } catch (IllegalArgumentException ignored) { }
                }
                if (internal != null) authorities.add(new SimpleGrantedAuthority("ROLE_" + internal));
            }
        }
        return new JwtAuthenticationToken(jwt, authorities, principalName(jwt));
    }

    private String principalName(Jwt jwt) {
        for (String claim : props.getSecurity().getIdentityClaims()) {
            String v = jwt.getClaimAsString(claim);
            if (v != null && !v.isBlank()) return v.toLowerCase(Locale.ROOT);
        }
        return jwt.getSubject();
    }
}
