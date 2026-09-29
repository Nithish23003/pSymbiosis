package com.psiog.perfinsight.scoring.measure;

import com.psiog.perfinsight.ingest.ToolType;

import java.util.List;
import java.util.Set;

/** raw == null means "no data for this measure" (never treated as zero). */
public record MeasureValue(Double raw, int sampleSize, List<Long> evidenceIds, Set<ToolType> tools) {
    public static MeasureValue none() {
        return new MeasureValue(null, 0, List.of(), Set.of());
    }
}
