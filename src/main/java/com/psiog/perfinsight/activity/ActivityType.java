package com.psiog.perfinsight.activity;

public enum ActivityType {
    TICKET,         // Jira issue / ADO work item
    PULL_REQUEST,   // authored PR
    CODE_REVIEW,    // review given on someone else's PR
    COMMIT,         // kept for drill-down only, never scored on its own
    TEST_RUN,       // test execution / result
    DOCUMENT,       // SharePoint document created / edited
    OTHER
}
