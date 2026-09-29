package com.psiog.perfinsight.org;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface OfferingRepository extends JpaRepository<Offering, Long> {
    Optional<Offering> findByCode(String code);
}
