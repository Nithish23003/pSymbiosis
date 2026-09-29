package com.psiog.perfinsight.model;

/**
 * Who a person is compared with. One standard method for everyone - percentile rank within the cohort -
 * which is what makes results comparable across projects that use different tools.
 */
public enum CohortScope {
    /** same persona across the whole organisation */
    PERSONA,
    /** same persona within the same offering (falls back to PERSONA when the cohort is too small) */
    PERSONA_OFFERING
}
