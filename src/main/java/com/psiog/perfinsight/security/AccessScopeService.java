package com.psiog.perfinsight.security;

import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.org.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@RequiredArgsConstructor
public class AccessScopeService {

    private final AssociateRepository associates;
    private final TeamRepository teams;
    private final ProjectAssignmentRepository assignments;
    private final com.psiog.perfinsight.config.AppProperties props;

    @Transactional(readOnly = true)
    public AccessScope current() {
        if (props.getSecurity().isOpenAccess()) {                        // <-- add this block
            return new AccessScope("open-access", EnumSet.allOf(AppRole.class), null, true, Set.of());
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) throw new ForbiddenException("Not authenticated");

        Set<AppRole> roles = EnumSet.noneOf(AppRole.class);
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if (a.startsWith("ROLE_")) {
                try { roles.add(AppRole.valueOf(a.substring(5))); } catch (IllegalArgumentException ignored) { }
            }
        }

        Optional<Associate> me = findAssociate(auth);
        Long myId = me.map(Associate::getId).orElse(null);

        boolean all = roles.contains(AppRole.ADMIN) || roles.contains(AppRole.SERVICE_HEAD);
        Set<Long> visible = new HashSet<>();
        if (myId != null) visible.add(myId);

        if (!all && roles.contains(AppRole.MANAGER) && myId != null) {
            // 1) members of teams I manage
            for (Team t : teams.findByManagerId(myId)) {
                associates.findByTeamId(t.getId()).forEach(a -> visible.add(a.getId()));
            }
            // 2) people on projects where I currently hold (or recently held) the LEAD role
            LocalDate today = LocalDate.now();
            for (ProjectAssignment pa : assignments.findByAssociateId(myId)) {
                boolean recent = pa.getValidTo() == null || !pa.getValidTo().isBefore(today.minusMonths(6));
                if (pa.getPersona() == Persona.LEAD && recent) {
                    assignments.findByProjectId(pa.getProject().getId())
                            .forEach(x -> visible.add(x.getAssociate().getId()));
                }
            }
        }
        return new AccessScope(auth.getName(), roles, myId, all, Collections.unmodifiableSet(visible));
    }

    public void requireCanSee(Long associateId) {
        if (!current().canSee(associateId)) throw new ForbiddenException("You do not have access to this associate's data");
    }

    private Optional<Associate> findAssociate(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwt) {
            String oid = jwt.getToken().getClaimAsString("oid");
            if (oid != null) {
                Optional<Associate> byOid = associates.findByAzureObjectId(oid);
                if (byOid.isPresent()) return byOid;
            }
        }
        return associates.findByEmailIgnoreCase(auth.getName());
    }
}
