package com.psiog.perfinsight.activity;

import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.security.AccessScope;
import com.psiog.perfinsight.security.AccessScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/** Drill-down to the underlying tickets, PRs, test runs and documents (criterion 7), access-filtered (criterion 10). */
@Service
@RequiredArgsConstructor
public class ActivityQueryService {

    private final ActivityRepository activities;
    private final EffectiveActivityService effective;
    private final AccessScopeService access;

    @Transactional(readOnly = true)
    public List<EffectiveActivity> search(LocalDate from, LocalDate to, Long associateId, Long projectId,
                                          ActivityType type, int limit) {
        AccessScope scope = access.current();
        if (associateId != null) access.requireCanSee(associateId);
        List<Activity> rows = activities.search(from, to, associateId, projectId, type, PageRequest.of(0, Math.min(limit, 2000)));
        return effective.effective(rows).stream()
                .filter(e -> scope.seesAll() || (e.associateId() != null && scope.canSee(e.associateId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public EffectiveActivity get(Long id) {
        Activity a = activities.findById(id).orElseThrow(() -> new NotFoundException("Activity", id));
        AccessScope scope = access.current();
        if (a.getAssociate() == null ? !scope.seesAll() && !scope.canEditData() : !scope.canSee(a.getAssociate().getId()))
            throw new com.psiog.perfinsight.common.ForbiddenException("No access to this activity");
        return effective.effective(a);
    }

    @Transactional(readOnly = true)
    public List<EffectiveActivity> byIds(Collection<Long> ids) {
        AccessScope scope = access.current();
        return effective.effective(activities.findByIdIn(ids)).stream()
                .filter(e -> scope.seesAll() || (e.associateId() != null && scope.canSee(e.associateId())))
                .toList();
    }
}
