package com.psiog.perfinsight.org;

import com.psiog.perfinsight.activity.AttributionService;
import com.psiog.perfinsight.audit.AuditService;
import com.psiog.perfinsight.common.BadRequestException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.org.OrgDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrgService {

    private final OfferingRepository offerings;
    private final TeamRepository teams;
    private final AssociateRepository associates;
    private final ProjectRepository projects;
    private final ProjectAssignmentRepository assignments;
    private final AttributionService attribution;
    private final AuditService audit;

    @Transactional
    public Offering saveOffering(Long id, OfferingRequest r) {
        Offering o = id == null ? new Offering() : offerings.findById(id).orElseThrow(() -> new NotFoundException("Offering", id));
        o.setCode(r.code());
        o.setName(r.name());
        o.setDescription(r.description());
        offerings.save(o);
        audit.log(id == null ? "OFFERING_CREATED" : "OFFERING_UPDATED", "Offering", o.getId(), Map.of("code", r.code()));
        return o;
    }

    @Transactional
    public Team saveTeam(Long id, TeamRequest r) {
        Team t = id == null ? new Team() : teams.findById(id).orElseThrow(() -> new NotFoundException("Team", id));
        t.setName(r.name());
        t.setOffering(r.offeringId() == null ? null : offerings.findById(r.offeringId()).orElseThrow(() -> new NotFoundException("Offering", r.offeringId())));
        t.setManager(r.managerId() == null ? null : associates.findById(r.managerId()).orElseThrow(() -> new NotFoundException("Associate", r.managerId())));
        teams.save(t);
        audit.log(id == null ? "TEAM_CREATED" : "TEAM_UPDATED", "Team", t.getId(), Map.of("name", r.name()));
        return t;
    }

    @Transactional
    public Associate saveAssociate(Long id, AssociateRequest r) {
        Associate a = id == null ? new Associate() : associates.findById(id).orElseThrow(() -> new NotFoundException("Associate", id));
        a.setEmployeeCode(r.employeeCode());
        a.setFullName(r.fullName());
        a.setEmail(r.email().toLowerCase());
        a.setAzureObjectId(r.azureObjectId());
        a.setTeam(r.teamId() == null ? null : teams.findById(r.teamId()).orElseThrow(() -> new NotFoundException("Team", r.teamId())));
        if (r.active() != null) a.setActive(r.active());
        associates.save(a);
        audit.log(id == null ? "ASSOCIATE_CREATED" : "ASSOCIATE_UPDATED", "Associate", a.getId(), Map.of("email", a.getEmail()));
        return a;
    }

    @Transactional
    public Project saveProject(Long id, ProjectRequest r) {
        Project p = id == null ? new Project() : projects.findById(id).orElseThrow(() -> new NotFoundException("Project", id));
        p.setCode(r.code());
        p.setName(r.name());
        p.setDescription(r.description());
        p.setOffering(offerings.findById(r.offeringId()).orElseThrow(() -> new NotFoundException("Offering", r.offeringId())));
        if (r.active() != null) p.setActive(r.active());
        projects.save(p);
        audit.log(id == null ? "PROJECT_CREATED" : "PROJECT_UPDATED", "Project", p.getId(), Map.of("code", r.code()));
        return p;
    }

    @Transactional
    public ProjectAssignment saveAssignment(Long id, AssignmentRequest r) {
        if (r.validTo() != null && r.validTo().isBefore(r.validFrom()))
            throw new BadRequestException("validTo must be on/after validFrom");
        ProjectAssignment pa = id == null ? new ProjectAssignment()
                : assignments.findById(id).orElseThrow(() -> new NotFoundException("Assignment", id));
        Associate a = associates.findById(r.associateId()).orElseThrow(() -> new NotFoundException("Associate", r.associateId()));
        Project p = projects.findById(r.projectId()).orElseThrow(() -> new NotFoundException("Project", r.projectId()));

        // no overlapping assignments for the same person on the same project
        for (ProjectAssignment other : assignments.findByAssociateId(a.getId())) {
            if (other.getId().equals(id) || !other.getProject().getId().equals(p.getId())) continue;
            LocalDate oEnd = other.getValidTo() == null ? LocalDate.MAX : other.getValidTo();
            LocalDate nEnd = r.validTo() == null ? LocalDate.MAX : r.validTo();
            if (!r.validFrom().isAfter(oEnd) && !other.getValidFrom().isAfter(nEnd))
                throw new BadRequestException("Overlaps assignment " + other.getId() + " on " + p.getCode()
                        + " - close it first (set validTo) or use /move");
        }
        pa.setAssociate(a);
        pa.setProject(p);
        pa.setPersona(r.persona());
        pa.setValidFrom(r.validFrom());
        pa.setValidTo(r.validTo());
        pa.setAllocationPercent(r.allocationPercent() == null ? 100 : r.allocationPercent());
        assignments.save(pa);

        Map<String, Object> d = new HashMap<>();
        d.put("associateId", a.getId());
        d.put("projectId", p.getId());
        d.put("persona", r.persona().name());
        d.put("validFrom", r.validFrom().toString());
        d.put("validTo", r.validTo() == null ? null : r.validTo().toString());
        audit.log(id == null ? "ASSIGNMENT_CREATED" : "ASSIGNMENT_UPDATED", "ProjectAssignment", pa.getId(), d);
        attribution.reattributeAssociate(a.getId());
        return pa;
    }

    /** Close the current assignment the day before effectiveFrom and open the new one. */
    @Transactional
    public ProjectAssignment move(MoveRequest r) {
        if (r.closeAssignmentId() != null) {
            ProjectAssignment old = assignments.findById(r.closeAssignmentId())
                    .orElseThrow(() -> new NotFoundException("Assignment", r.closeAssignmentId()));
            old.setValidTo(r.effectiveFrom().minusDays(1));
            assignments.save(old);
        } else {
            assignments.findByAssociateId(r.associateId()).stream()
                    .filter(pa -> pa.getValidTo() == null && pa.getValidFrom().isBefore(r.effectiveFrom()))
                    .forEach(pa -> { pa.setValidTo(r.effectiveFrom().minusDays(1)); assignments.save(pa); });
        }
        return saveAssignment(null, new AssignmentRequest(r.associateId(), r.toProjectId(), r.persona(),
                r.effectiveFrom(), null, r.allocationPercent()));
    }

    @Transactional
    public void deleteAssignment(Long id) {
        ProjectAssignment pa = assignments.findById(id).orElseThrow(() -> new NotFoundException("Assignment", id));
        Long associateId = pa.getAssociate().getId();
        assignments.delete(pa);
        audit.log("ASSIGNMENT_DELETED", "ProjectAssignment", id, Map.of("associateId", associateId));
        attribution.reattributeAssociate(associateId);
    }
}
