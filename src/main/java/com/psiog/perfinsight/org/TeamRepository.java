package com.psiog.perfinsight.org;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TeamRepository extends JpaRepository<Team, Long> {
    List<Team> findByManagerId(Long managerId);
}
