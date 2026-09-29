package com.psiog.perfinsight.scoring.measure;

import com.psiog.perfinsight.activity.EffectiveActivity;

import java.util.List;
import java.util.function.Function;

/**
 * A measure the model can weight. rateBased measures are pro-rated per N active working days, so partial
 * periods and context-note exclusions are fair. Ratios / medians are not pro-rated.
 */
public record MeasureDefinition(
        String key,
        String label,
        String category,
        String description,
        MeasureDirection direction,
        boolean rateBased,
        String unit,
        Function<List<EffectiveActivity>, MeasureValue> calculator) {

    public MeasureValue compute(List<EffectiveActivity> activities) {
        return calculator.apply(activities);
    }
}
