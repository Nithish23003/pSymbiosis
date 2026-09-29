package com.psiog.perfinsight.insight;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AnomalyFlagRepository extends JpaRepository<AnomalyFlag, Long> {

    Optional<AnomalyFlag> findByAssociateIdAndRuleKeyAndPeriodStartAndPeriodEnd(Long associateId, String ruleKey, LocalDate start, LocalDate end);

    @Query("""
           select f from AnomalyFlag f
           where f.periodStart <= :to and f.periodEnd >= :from
             and (:associateId is null or f.associate.id = :associateId)
             and (:projectId is null or f.project.id = :projectId)
           order by f.detectedAt desc
           """)
    List<AnomalyFlag> search(@Param("from") LocalDate from, @Param("to") LocalDate to,
                             @Param("associateId") Long associateId, @Param("projectId") Long projectId);
}
