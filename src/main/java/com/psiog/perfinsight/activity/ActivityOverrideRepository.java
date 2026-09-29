package com.psiog.perfinsight.activity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface ActivityOverrideRepository extends JpaRepository<ActivityOverride, Long> {
    List<ActivityOverride> findByActivityIdInAndActiveTrueOrderByIdAsc(Collection<Long> activityIds);
    List<ActivityOverride> findByActivityIdOrderByIdAsc(Long activityId);
}
