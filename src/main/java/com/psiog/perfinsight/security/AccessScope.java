package com.psiog.perfinsight.security;

import java.util.Set;

/**
 * What the signed-in user may see.
 * seesAll = SERVICE_HEAD / ADMIN. Otherwise only visibleAssociateIds (self for EMPLOYEE, team for MANAGER).
 */
public record AccessScope(String userName, Set<AppRole> roles, Long associateId, boolean seesAll, Set<Long> visibleAssociateIds) {

    public boolean canSee(Long associateId) {
        return seesAll || (associateId != null && visibleAssociateIds.contains(associateId));
    }

    public boolean has(AppRole role) {
        return roles.contains(role);
    }

    public boolean canEditData() {
        return has(AppRole.ADMIN) || has(AppRole.SERVICE_HEAD) || has(AppRole.MANAGER);
    }
}
