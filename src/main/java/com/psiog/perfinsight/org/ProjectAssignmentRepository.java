package com.psiog.perfinsight.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ProjectAssignmentRepository extends JpaRepository<ProjectAssignment, Long> {

    List<ProjectAssignment> findByAssociateId(Long associateId);

    List<ProjectAssignment> findByProjectId(Long projectId);

    @Query("""
           select pa from ProjectAssignment pa
           where pa.validFrom <= :end and (pa.validTo is null or pa.validTo >= :start)
           """)
    List<ProjectAssignment> findOverlapping(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
           select pa from ProjectAssignment pa
           where pa.associate.id = :associateId
             and pa.validFrom <= :end and (pa.validTo is null or pa.validTo >= :start)
           order by pa.validFrom
           """)
    List<ProjectAssignment> findOverlappingForAssociate(@Param("associateId") Long associateId,
                                                        @Param("start") LocalDate start,
                                                        @Param("end") LocalDate end);
}
