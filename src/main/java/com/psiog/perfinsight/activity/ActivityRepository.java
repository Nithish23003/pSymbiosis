package com.psiog.perfinsight.activity;

import com.psiog.perfinsight.ingest.ToolType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    Optional<Activity> findBySourceToolAndExternalIdAndActivityType(ToolType tool, String externalId, ActivityType type);

    List<Activity> findByToolAccountId(Long toolAccountId);

    List<Activity> findByAssociateId(Long associateId);

    @Query("""
           select a from Activity a
           where a.occurredOn between :from and :to and a.associate is not null
           """)
    List<Activity> findAttributableBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
           select a from Activity a
           where a.occurredOn between :from and :to
             and (:associateId is null or a.associate.id = :associateId)
             and (:projectId is null or a.project.id = :projectId)
             and (:type is null or a.activityType = :type)
           order by a.occurredAt desc
           """)
    List<Activity> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
                          @Param("associateId") Long associateId, @Param("projectId") Long projectId,
                          @Param("type") ActivityType type, Pageable page);

    @Query("""
           select a from Activity a
           where a.associate is null and a.occurredOn between :from and :to
             and (:projectId is null or a.sourceProject.id = :projectId)
           order by a.occurredAt desc
           """)
    List<Activity> findUnmatched(@Param("from") LocalDate from, @Param("to") LocalDate to,
                                 @Param("projectId") Long projectId, Pageable page);

    @Query("select a.toolAccount.id, count(a) from Activity a where a.associate is null and a.toolAccount is not null group by a.toolAccount.id")
    List<Object[]> countUnmatchedByAccount();

    List<Activity> findByIdIn(Collection<Long> ids);
}
