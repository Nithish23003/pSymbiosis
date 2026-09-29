package com.psiog.perfinsight.org;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface AssociateRepository extends JpaRepository<Associate, Long> {
    Optional<Associate> findByEmailIgnoreCase(String email);
    Optional<Associate> findByAzureObjectId(String oid);
    List<Associate> findByTeamId(Long teamId);
    List<Associate> findByActiveTrue();
}
