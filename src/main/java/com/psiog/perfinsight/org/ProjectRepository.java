package com.psiog.perfinsight.org;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findByCode(String code);
    List<Project> findByOfferingId(Long offeringId);
}
