package com.psiog.perfinsight.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/** @PreAuthorize checks are active only when open-access is OFF. */
@Configuration
@EnableMethodSecurity
@ConditionalOnProperty(prefix = "app.security", name = "open-access", havingValue = "false", matchIfMissing = true)
public class MethodSecurityConfig {
}