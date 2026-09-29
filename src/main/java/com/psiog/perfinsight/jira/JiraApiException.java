package com.psiog.perfinsight.jira;

import lombok.Getter;

/** A Jira REST call failed; status is Jira's HTTP status (0 = not reachable / not configured). */
@Getter
public class JiraApiException extends RuntimeException {
    private final int status;

    public JiraApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
