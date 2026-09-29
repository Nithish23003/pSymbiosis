package com.psiog.perfinsight.notes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface ContextNoteRepository extends JpaRepository<ContextNote, Long> {

    @Query("""
           select n from ContextNote n
           where n.startDate <= :end and n.endDate >= :start
           """)
    List<ContextNote> findOverlapping(@Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("""
           select n from ContextNote n
           where n.associate.id = :associateId and n.startDate <= :end and n.endDate >= :start
           order by n.startDate
           """)
    List<ContextNote> findForAssociate(@Param("associateId") Long associateId,
                                       @Param("start") LocalDate start, @Param("end") LocalDate end);
}
