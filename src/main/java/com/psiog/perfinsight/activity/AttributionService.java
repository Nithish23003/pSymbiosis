package com.psiog.perfinsight.activity;

import com.psiog.perfinsight.org.ProjectAssignment;
import com.psiog.perfinsight.org.ProjectAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Acceptance criterion 3: activity is attributed to the project + role the person held ON THE EVENT DATE,
 * so mid-period moves and promotions are handled naturally.
 */
@Service
@RequiredArgsConstructor
public class AttributionService {

    private final ProjectAssignmentRepository assignments;
    private final ActivityRepository activities;

    public void attribute(Activity a) {
        if (a.getAssociate() == null) {
            a.setProject(a.getSourceProject());
            a.setPersona(null);
            a.setAttributionStatus(AttributionStatus.UNMATCHED_IDENTITY);
            return;
        }
        List<ProjectAssignment> covering = assignments.findByAssociateId(a.getAssociate().getId()).stream()
                .filter(pa -> pa.covers(a.getOccurredOn()))
                .toList();

        Optional<ProjectAssignment> onSourceProject = covering.stream()
                .filter(pa -> a.getSourceProject() != null && pa.getProject().getId().equals(a.getSourceProject().getId()))
                .findFirst();

        ProjectAssignment chosen = onSourceProject.orElseGet(() -> covering.stream()
                .max(Comparator.comparing(pa -> pa.getAllocationPercent() == null ? 0 : pa.getAllocationPercent()))
                .orElse(null));

        if (chosen == null) {
            a.setProject(a.getSourceProject());
            a.setPersona(null);
            a.setAttributionStatus(AttributionStatus.NO_ASSIGNMENT);
        } else {
            a.setProject(chosen.getProject());
            a.setPersona(chosen.getPersona());
            a.setAttributionStatus(AttributionStatus.ATTRIBUTED);
        }
    }

    /** Re-run after assignments change or an identity is (re)linked. */
    @Transactional
    public int reattributeAssociate(Long associateId) {
        List<Activity> list = activities.findByAssociateId(associateId);
        list.forEach(this::attribute);
        activities.saveAll(list);
        return list.size();
    }
}
