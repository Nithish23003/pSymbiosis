package com.psiog.perfinsight.jira;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class JiraDtos {
    private JiraDtos() {}

    public record JiraUser(String accountId, String displayName, String email, boolean active, String accountType,
                           Double matchScore) {}

    public record ProjectInfo(String key, String name, String id, boolean userIsAssignable) {}

    public record Tickets(int total, int completed, int inProgress, int toDo,
                          Map<String, Integer> byStatus, Map<String, Integer> byType) {}

    public record StoryPoints(double total, double completed, double inProgress, double toDo,
                              int ticketsWithoutEstimate, List<String> fieldsUsed) {}

    public record TimeLogged(long seconds, double hours, String pretty, int worklogCount, int issuesWorkedOn,
                             long originalEstimateSecondsOnAssigned, Map<String, Double> hoursByIssue) {}

    public record Development(boolean available, int commitsLinkedToHisIssues, int commitsAuthoredByHim,
                              int pullRequestsLinkedToHisIssues, int pullRequestsAuthoredByHim,
                              int pullRequestsMerged, int pullRequestsOpen, int issuesWithDevActivity,
                              List<String> sources, String note) {}

    public record IssueRow(String key, String id, String summary, String type, String status, String statusCategory,
                           String priority, Double storyPoints, Double hoursLoggedByHim, String created,
                           String resolved, int commits, int pullRequests, String url) {}

    public record UserSummary(JiraUser user, ProjectInfo project, LocalDate from, LocalDate to, String jql,
                              Tickets tickets, StoryPoints storyPoints, TimeLogged timeLogged,
                              Development development, List<IssueRow> issues, List<String> warnings) {}
}
