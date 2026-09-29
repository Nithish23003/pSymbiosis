package com.psiog.perfinsight.security;

/**
 * Internal roles. Azure AD app-role values are mapped to these via app.security.role-mapping.
 * EMPLOYEE -> own data, MANAGER -> their team, SERVICE_HEAD -> everything, ADMIN -> configuration (+ everything).
 */
public enum AppRole {
    ADMIN, SERVICE_HEAD, MANAGER, EMPLOYEE;

    public String authority() {
        return "ROLE_" + name();
    }
}
