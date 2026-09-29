package com.psiog.perfinsight.scoring;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScoringMathTest {

    @Test
    void midRankPercentileHandlesTies() {
        assertThat(ScoringService.midRankPercentile(List.of(1.0, 2.0, 3.0, 4.0), 4.0)).isEqualTo(87.5);
        assertThat(ScoringService.midRankPercentile(List.of(2.0, 2.0, 2.0), 2.0)).isEqualTo(50.0);
    }

    @Test
    void noSingleMeasureExceedsCap() {
        Map<String, Double> w = new LinkedHashMap<>();
        w.put("a", 80.0);
        w.put("b", 10.0);
        w.put("c", 10.0);
        Map<String, Double> eff = ScoringService.capAndNormalise(w, 0.4);
        assertThat(eff.values().stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(1.0, within(1e-6));
        assertThat(eff.get("a")).isLessThanOrEqualTo(0.4 + 1e-9);
        assertThat(eff.get("b")).isCloseTo(0.3, within(1e-6));
    }
}
