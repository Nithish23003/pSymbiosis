package com.psiog.perfinsight.insight;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiInsightRepository extends JpaRepository<AiInsight, Long> {
    List<AiInsight> findTop10BySubjectTypeAndSubjectIdOrderByIdDesc(String subjectType, Long subjectId);
}
