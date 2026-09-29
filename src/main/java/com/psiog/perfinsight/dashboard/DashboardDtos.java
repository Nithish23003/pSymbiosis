package com.psiog.perfinsight.dashboard;

import jakarta.validation.constraints.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
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

    public record ProjectDto(Long id, String code, String name, String client, String offering, String manager,
                             String status, String priority, Integer plannedTeamSize,
                             LocalDate startDate, LocalDate endDate,
                             Double reportedCompletionPct, String reportedHealth, String description,
                             boolean active, Double timeElapsedPct, boolean overdue,
                             LocalDateTime createdDate, LocalDateTime updatedDate) {
        static ProjectDto of(DashProject p) {
            return new ProjectDto(p.getId(), p.getProjectCode(), p.getProjectName(), p.getClientName(),
                    p.getOffering(), p.getProjectManager(), p.getStatus(), p.getPriority(), p.getTeamSize(),
                    p.getStartDate(), p.getEndDate(),
                    p.getCompletionPercentage() == null ? null : p.getCompletionPercentage().doubleValue(),
                    p.getHealthStatus(), p.getProjectDescription(),
                    !Boolean.FALSE.equals(p.getActive()), ProjectDashboardService.timeElapsedPct(p),
                    ProjectDashboardService.isOverdue(p), p.getCreatedDate(), p.getUpdatedDate());
        }
    }

    /** Body for create / update. Status, priority and health are stored upper-case (e.g. "In Progress" -> IN_PROGRESS). */
    public record ProjectRequest(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 100) String name,
            @Size(max = 100) String client,
            @Size(max = 100) String offering,
            @Size(max = 100) String manager,
            @Size(max = 30) String status,
            @Size(max = 20) String priority,
            @PositiveOrZero Integer plannedTeamSize,
            LocalDate startDate,
            LocalDate endDate,
            @DecimalMin("0.0") @DecimalMax("100.0") Double completionPct,
            @Size(max = 20) String health,
            @Size(max = 500) String description,
            Boolean active) {}

    /** Portfolio view over the projects table. */
    public record ProjectPortfolio(int totalProjects, int activeProjects, int plannedTeamSize, Double avgCompletionPct,
                                   Map<String, Integer> byStatus, Map<String, Integer> byPriority,
                                   Map<String, Integer> byHealth, Map<String, Integer> byOffering,
                                   List<ProjectDto> overdue, List<ProjectDto> endingSoon,
                                   List<ProjectDto> atRisk, List<ProjectDto> behindSchedule,
                                   List<String> insights) {}

    public record ProjectSummary(ProjectDto project, String health, List<String> healthReasons, int teamSize,
                                 int totalStories, int completedStories, int inProgressStories, int toDoStories,
                                 int totalStoryPoints, int completedStoryPoints, Double progressPct,
                                 Double avgCycleTimeDays, int pullRequests, int mergedPullRequests, int commits,
                                 Double timeElapsedPct) {}
}