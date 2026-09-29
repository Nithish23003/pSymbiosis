package com.psiog.perfinsight.scoring;

public enum ScoreStatus {
    SCORED,
    /** not enough measures / tools / active days - we say so instead of guessing */
    INSUFFICIENT_DATA
}
