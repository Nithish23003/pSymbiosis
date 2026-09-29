package com.psiog.perfinsight.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AssociateAliasRepository extends JpaRepository<AssociateAlias, Long> {
    List<AssociateAlias> findByAliasValue(String aliasValue);
    List<AssociateAlias> findByAssociateId(Long associateId);
}
