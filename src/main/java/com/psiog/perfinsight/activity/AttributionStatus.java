package com.psiog.perfinsight.activity;

public enum AttributionStatus {
    ATTRIBUTED,          // person + project + role known for the event date
    NO_ASSIGNMENT,       // person known, but no project role covers the event date
    UNMATCHED_IDENTITY   // tool account not linked to a person yet
}
