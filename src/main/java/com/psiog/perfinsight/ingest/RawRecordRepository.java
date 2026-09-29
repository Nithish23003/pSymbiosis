package com.psiog.perfinsight.ingest;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface RawRecordRepository extends JpaRepository<RawRecord, Long> {
    Optional<RawRecord> findTopByToolConfigIdAndRecordKindAndExternalIdOrderByIdDesc(Long toolConfigId, String recordKind, String externalId);
}
