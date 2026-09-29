package com.psiog.perfinsight.org;

import com.psiog.perfinsight.org.OrgDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** ADMIN only (secured by /api/admin/** rule). */
@RestController
@RequestMapping("/api/admin/org")
@RequiredArgsConstructor
public class OrgAdminController {

    private final OrgService org;

    @PostMapping("/offerings")
    public OfferingDto createOffering(@Valid @RequestBody OfferingRequest r) { return OfferingDto.of(org.saveOffering(null, r)); }

    @PutMapping("/offerings/{id}")
    public OfferingDto updateOffering(@PathVariable Long id, @Valid @RequestBody OfferingRequest r) { return OfferingDto.of(org.saveOffering(id, r)); }

    @PostMapping("/teams")
    public TeamDto createTeam(@Valid @RequestBody TeamRequest r) { return TeamDto.of(org.saveTeam(null, r)); }

    @PutMapping("/teams/{id}")
    public TeamDto updateTeam(@PathVariable Long id, @Valid @RequestBody TeamRequest r) { return TeamDto.of(org.saveTeam(id, r)); }

    @PostMapping("/associates")
    public AssociateDto createAssociate(@Valid @RequestBody AssociateRequest r) { return AssociateDto.of(org.saveAssociate(null, r)); }

    @PutMapping("/associates/{id}")
    public AssociateDto updateAssociate(@PathVariable Long id, @Valid @RequestBody AssociateRequest r) { return AssociateDto.of(org.saveAssociate(id, r)); }

    @PostMapping("/projects")
    public ProjectDto createProject(@Valid @RequestBody ProjectRequest r) { return ProjectDto.of(org.saveProject(null, r)); }

    @PutMapping("/projects/{id}")
    public ProjectDto updateProject(@PathVariable Long id, @Valid @RequestBody ProjectRequest r) { return ProjectDto.of(org.saveProject(id, r)); }

    @PostMapping("/assignments")
    public AssignmentDto createAssignment(@Valid @RequestBody AssignmentRequest r) { return AssignmentDto.of(org.saveAssignment(null, r)); }

    @PutMapping("/assignments/{id}")
    public AssignmentDto updateAssignment(@PathVariable Long id, @Valid @RequestBody AssignmentRequest r) { return AssignmentDto.of(org.saveAssignment(id, r)); }

    @DeleteMapping("/assignments/{id}")
    public void deleteAssignment(@PathVariable Long id) { org.deleteAssignment(id); }

    /** Project move or role change mid-period. */
    @PostMapping("/assignments/move")
    public AssignmentDto move(@Valid @RequestBody MoveRequest r) { return AssignmentDto.of(org.move(r)); }
}
