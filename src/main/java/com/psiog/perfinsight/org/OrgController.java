package com.psiog.perfinsight.org;

import com.psiog.perfinsight.org.OrgDtos.*;
import com.psiog.perfinsight.security.AccessScope;
import com.psiog.perfinsight.security.AccessScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only org data for every signed-in user, filtered to what they may see. */
@RestController
@RequestMapping("/api/org")
@RequiredArgsConstructor
public class OrgController {

    private final OfferingRepository offerings;
    private final TeamRepository teams;
    private final AssociateRepository associates;
    private final ProjectRepository projects;
    private final ProjectAssignmentRepository assignments;
    private final AccessScopeService access;

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public Map<String, Object> me() {
        AccessScope s = access.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user", s.userName());
        m.put("roles", s.roles());
        m.put("associate", s.associateId() == null ? null : associates.findById(s.associateId()).map(AssociateDto::of).orElse(null));
        m.put("seesAll", s.seesAll());
        m.put("visibleAssociateCount", s.seesAll() ? associates.count() : s.visibleAssociateIds().size());
        return m;
    }

    @GetMapping("/offerings")
    @Transactional(readOnly = true)
    public List<OfferingDto> offerings() { return offerings.findAll().stream().map(OfferingDto::of).toList(); }

    @GetMapping("/teams")
    @Transactional(readOnly = true)
    public List<TeamDto> teams() { return teams.findAll().stream().map(TeamDto::of).toList(); }

    @GetMapping("/projects")
    @Transactional(readOnly = true)
    public List<ProjectDto> projects() { return projects.findAll().stream().map(ProjectDto::of).toList(); }

    @GetMapping("/associates")
    @Transactional(readOnly = true)
    public List<AssociateDto> associates() {
        AccessScope s = access.current();
        return associates.findAll().stream().filter(a -> s.canSee(a.getId())).map(AssociateDto::of).toList();
    }

    @GetMapping("/associates/{id}/assignments")
    @Transactional(readOnly = true)
    public List<AssignmentDto> assignmentsFor(@PathVariable Long id) {
        access.requireCanSee(id);
        return assignments.findByAssociateId(id).stream().map(AssignmentDto::of).toList();
    }

    @GetMapping("/projects/{id}/assignments")
    @Transactional(readOnly = true)
    public List<AssignmentDto> projectAssignments(@PathVariable Long id) {
        AccessScope s = access.current();
        return assignments.findByProjectId(id).stream()
                .filter(pa -> s.canSee(pa.getAssociate().getId()))
                .map(AssignmentDto::of).toList();
    }
}
