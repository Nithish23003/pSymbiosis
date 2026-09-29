package com.psiog.perfinsight.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class DashboardDtos {
    private DashboardDtos() {}

    public record UserDto(Long id, String employeeId, String name, String email, String role, boolean active) {
        static UserDto of(DashUser u) {
            return new UserDto(u.getId(), u.getEmployeeId(), u.getName(), u.getEmail(), u.getRole(),
                    !Boolean.FALSE.equals(u.getActive()));
        }
    }

    /** One line of "how the score was reached". */
    public record ScorePart(String metric, Double value, Double normalised, double weight,
                            Double effectiveWeight, Double contribution, boolean included, String note) {}

    public record DevMetrics(Long userId, String employeeId, String name, String email, String role,
                             int totalStories, int completedStories, int inProgressStories, int toDoStories,
                             int totalStoryPoints, int completedStoryPoints, Double completionRatePct,
                             Double avgCycleTimeDays,
                             int pullRequests, int mergedPullRequests, Double prMergeRatePct,
                             int commits, int linesAdded, int linesRemoved,
                             Double score, String band, Integer rank, List<ScorePart> scoreBreakdown) {}

    public record WeekPoint(LocalDate weekStart, int storiesCompleted, int pointsCompleted,
                            int prsMerged, int commits) {}

    public record StoryDto(String storyId, String title, Integer storyPoints, String status,
                           LocalDate createdDate, LocalDate completedDate, Long cycleDays, String jiraUrl) {}

    public record PrDto(String repository, String pullRequestId, Integer commits, Integer linesAdded,
                        Integer linesRemoved, boolean merged, LocalDate activityDate) {}

    public record Overview(LocalDate from, LocalDate to, Map<String, Object> kpis,
                           Map<String, Integer> statusDistribution,
                           List<Map<String, Object>> storyPointsByDeveloper,
                           List<Map<String, Object>> githubByDeveloper,
                           Map<String, Map<String, Object>> repositories,
                           List<WeekPoint> weeklyTrend,
                           List<DevMetrics> leaderboard,
                           List<String> insights,
                           String method) {}

    public record DeveloperDetail(DevMetrics metrics, int teamSize, List<StoryDto> stories,
                                  List<PrDto> pullRequests, List<String> insights,
                                  Object liveJira, String method) {}
}