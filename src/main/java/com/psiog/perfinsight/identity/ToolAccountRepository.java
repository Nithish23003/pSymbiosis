package com.psiog.perfinsight.identity;

import com.psiog.perfinsight.ingest.ToolType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ToolAccountRepository extends JpaRepository<ToolAccount, Long> {
    Optional<ToolAccount> findByToolTypeAndExternalId(ToolType toolType, String externalId);
    List<ToolAccount> findByMatchStatus(MatchStatus status);
    List<ToolAccount> findByAssociateId(Long associateId);
}
