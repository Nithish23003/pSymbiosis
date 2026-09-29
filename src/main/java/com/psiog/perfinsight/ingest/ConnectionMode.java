package com.psiog.perfinsight.ingest;

/**
 * LIVE  - call the real tool API.
 * MOCK  - read a sample document shaped exactly like the real API response (swap to LIVE later with no other change).
 * FILE  - no scheduled pull; data arrives via export uploads (CSV / JSON).
 */
public enum ConnectionMode {
    LIVE, MOCK, FILE
}
