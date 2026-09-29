package com.psiog.perfinsight.dashboard;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.dashboard.DashboardDtos.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.ParseException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/** Email + password login against the users table; issues an HS256 JWT. */
@Slf4j
@Service
public class AuthService {

    public record LoginResult(String token, String tokenType, Instant expiresAt, UserDto user) {}

    private final DashUserRepository users;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final byte[] secret;
    private final long tokenMinutes;

    public AuthService(DashUserRepository users,
                       @Value("${app.auth.jwt-secret:}") String secret,          // default = empty, never fails
                       @Value("${app.auth.token-minutes:480}") long tokenMinutes) {
        this.users = users;
        this.tokenMinutes = tokenMinutes;
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            // no / short secret configured -> random one (tokens become invalid after a restart)
            byte[] random = new byte[32];
            new java.security.SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("app.auth.jwt-secret missing or < 32 chars - using a random secret. Set APP_JWT_SECRET to keep logins across restarts.");
        } else {
            this.secret = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    @Transactional
    public LoginResult login(String email, String password) {
        DashUser u = users.findByEmailIgnoreCase(email == null ? "" : email.trim())
                .orElseThrow(AuthService::invalid);          // same message for unknown email and wrong password
        if (Boolean.FALSE.equals(u.getActive())) throw new ForbiddenException("This account is disabled");

        String stored = u.getPassword();
        boolean ok;
        if (stored == null || stored.isBlank()) {
            ok = false;
        } else if (stored.startsWith("$2a$") || stored.startsWith("$2b$") || stored.startsWith("$2y$")) {
            ok = encoder.matches(password, stored);
        } else {
            // legacy plain-text row: constant-time compare, then upgrade to BCrypt
            ok = MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8));
            if (ok) {
                u.setPassword(encoder.encode(password));
                users.save(u);
                log.info("Upgraded password of {} to BCrypt", u.getEmail());
            }
        }
        if (!ok) throw invalid();

        Instant exp = Instant.now().plus(tokenMinutes, ChronoUnit.MINUTES);
        return new LoginResult(issue(u, exp), "Bearer", exp, UserDto.of(u));
    }

    /** The user behind "Authorization: Bearer <token>", or 401. */
    @Transactional(readOnly = true)
    public DashUser requireUser(String authorizationHeader) {
        return fromHeader(authorizationHeader)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in again"));
    }

    /** Email of the logged-in user if a valid token is present, otherwise the fallback. */
    public String viewerEmail(String authorizationHeader, String fallback) {
        return fromHeader(authorizationHeader).map(DashUser::getEmail).orElse(fallback);
    }

    // ---------------------------------------------------------------------------------------------

    private Optional<DashUser> fromHeader(String header) {
        if (header == null || !header.startsWith("Bearer ")) return Optional.empty();
        try {
            SignedJWT jwt = SignedJWT.parse(header.substring(7).trim());
            if (!jwt.verify(new MACVerifier(secret))) return Optional.empty();
            JWTClaimsSet c = jwt.getJWTClaimsSet();
            if (c.getExpirationTime() == null || c.getExpirationTime().before(new Date())) return Optional.empty();
            return users.findById(Long.parseLong(c.getSubject()))
                    .filter(u -> !Boolean.FALSE.equals(u.getActive()));
        } catch (ParseException | JOSEException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String issue(DashUser u, Instant exp) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer("perf-insight")
                    .subject(String.valueOf(u.getId()))
                    .claim("email", u.getEmail())
                    .claim("name", u.getName())
                    .claim("role", u.getRole())
                    .issueTime(new Date())
                    .expirationTime(Date.from(exp))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign token", e);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
    }
}