package com.psiog.perfinsight.dashboard;

import com.psiog.perfinsight.common.BadRequestException;
import com.psiog.perfinsight.common.ForbiddenException;
import com.psiog.perfinsight.common.NotFoundException;
import com.psiog.perfinsight.dashboard.DashboardDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** CRUD + portfolio view over the projects table. Employees can read; only other roles can write. */
@Service
@RequiredArgsConstructor
public class ProjectDashboardService {

    /** Health values treated as "at risk" in the portfolio. */
    private static final Set<String> RISKY_HEALTH = Set.of("RED", "AMBER", "AT_RISK", "OFF_TRACK", "CRITICAL");
    private static final Set<String> CLOSED_STATUS = Set.of("COMPLETED", "DONE", "CLOSED", "CANCELLED");
    /** completion this many points below time elapsed = behind schedule */
    private static final double BEHIND_SCHEDULE_GAP = 20.0;
    private static final int ENDING_SOON_DAYS = 30;

    private final DashProjectRepository projects;
    private final DashUserRepository users;

    // ============================================================================================
    // Read
    // ============================================================================================
    @Transactional(readOnly = true)
    public List<ProjectDto> list(String status, String priority, String health, String manager, String client,
                                 String offering, String q, boolean includeInactive) {
        String needle = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        return projects.findAll().stream()
                .filter(p -> includeInactive || isActive(p))
                .filter(p -> status == null || code(status).equals(code(p.getStatus())))
                .filter(p -> priority == null || code(priority).equals(code(p.getPriority())))
                .filter(p -> health == null || code(health).equals(code(p.getHealthStatus())))
                .filter(p -> manager == null || manager.trim().equalsIgnoreCase(trim(p.getProjectManager())))
                .filter(p -> client == null || client.trim().equalsIgnoreCase(trim(p.getClientName())))
                .filter(p -> offering == null || offering.trim().equalsIgnoreCase(trim(p.getOffering())))
                .filter(p -> needle == null || contains(p.getProjectCode(), needle) || contains(p.getProjectName(), needle)
                        || contains(p.getClientName(), needle) || contains(p.getProjectDescription(), needle))
                .sorted(Comparator.comparing(DashProject::getProjectName, String.CASE_INSENSITIVE_ORDER))
                .map(ProjectDto::of)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectDto get(Long id) {
        return ProjectDto.of(find(id));
    }

    @Transactional(readOnly = true)
    public ProjectDto byCode(String code) {
        return ProjectDto.of(projects.findByProjectCodeIgnoreCase(code.trim())
                .orElseThrow(() -> new NotFoundException("Project with code", code)));
    }

    @Transactional(readOnly = true)
    public ProjectPortfolio portfolio(boolean includeInactive) {
        List<DashProject> all = projects.findAll();
        List<DashProject> ps = all.stream().filter(p -> includeInactive || isActive(p))
                .sorted(Comparator.comparing(DashProject::getProjectName, String.CASE_INSENSITIVE_ORDER)).toList();
        LocalDate today = LocalDate.now();

        OptionalDouble avg = ps.stream().filter(p -> p.getCompletionPercentage() != null)
                .mapToDouble(p -> p.getCompletionPercentage().doubleValue()).average();

        List<ProjectDto> overdue = ps.stream().filter(ProjectDashboardService::isOverdue).map(ProjectDto::of).toList();
        List<ProjectDto> endingSoon = ps.stream()
                .filter(p -> !isClosed(p) && p.getEndDate() != null && !p.getEndDate().isBefore(today)
                        && !p.getEndDate().isAfter(today.plusDays(ENDING_SOON_DAYS)))
                .sorted(Comparator.comparing(DashProject::getEndDate))
                .map(ProjectDto::of).toList();
        List<ProjectDto> atRisk = ps.stream().filter(p -> !isClosed(p) && RISKY_HEALTH.contains(code(p.getHealthStatus())))
                .map(ProjectDto::of).toList();
        List<ProjectDto> behind = ps.stream().filter(ProjectDashboardService::isBehindSchedule).map(ProjectDto::of).toList();

        List<String> insights = new ArrayList<>();
        if (!overdue.isEmpty()) insights.add(overdue.size() + " project(s) are past their end date and not completed.");
        if (!endingSoon.isEmpty()) insights.add(endingSoon.size() + " project(s) end in the next " + ENDING_SOON_DAYS + " days.");
        if (!atRisk.isEmpty()) insights.add(atRisk.size() + " project(s) are reported as at risk ("
                + atRisk.stream().map(ProjectDto::code).collect(Collectors.joining(", ")) + ").");
        behind.forEach(p -> insights.add(p.code() + " is " + p.reportedCompletionPct() + "% complete with "
                + p.timeElapsedPct() + "% of its timeline used."));
        long noManager = ps.stream().filter(p -> trim(p.getProjectManager()) == null).count();
        if (noManager > 0) insights.add(noManager + " project(s) have no project manager.");
        long noDates = ps.stream().filter(p -> p.getStartDate() == null || p.getEndDate() == null).count();
        if (noDates > 0) insights.add(noDates + " project(s) are missing a start or end date.");

        return new ProjectPortfolio(ps.size(), (int) all.stream().filter(ProjectDashboardService::isActive).count(),
                ps.stream().mapToInt(p -> p.getTeamSize() == null ? 0 : p.getTeamSize()).sum(),
                avg.isPresent() ? round(avg.getAsDouble()) : null,
                countBy(ps, p -> code(p.getStatus())), countBy(ps, p -> code(p.getPriority())),
                countBy(ps, p -> code(p.getHealthStatus())), countBy(ps, p -> trim(p.getOffering())),
                overdue, endingSoon, atRisk, behind, insights);
    }

    // ============================================================================================
    // Write
    // ============================================================================================
    @Transactional
    public ProjectDto create(ProjectRequest r, String viewerEmail) {
        requireWriter(viewerEmail);
        if (projects.existsByProjectCodeIgnoreCase(r.code().trim()))
            throw new BadRequestException("Project code already exists: " + r.code().trim());
        DashProject p = new DashProject();
        apply(p, r);
        return ProjectDto.of(projects.save(p));
    }

    @Transactional
    public ProjectDto update(Long id, ProjectRequest r, String viewerEmail) {
        requireWriter(viewerEmail);
        DashProject p = find(id);
        projects.findByProjectCodeIgnoreCase(r.code().trim())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> { throw new BadRequestException("Project code already exists: " + r.code().trim()); });
        apply(p, r);
        return ProjectDto.of(projects.saveAndFlush(p));
    }

    /** Soft delete: sets active = false so history stays intact. */
    @Transactional
    public ProjectDto deactivate(Long id, String viewerEmail) {
        requireWriter(viewerEmail);
        DashProject p = find(id);
        p.setActive(false);
        return ProjectDto.of(projects.saveAndFlush(p));
    }

    // ============================================================================================
    // helpers
    // ============================================================================================
    private void apply(DashProject p, ProjectRequest r) {
        if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate()))
            throw new BadRequestException("endDate must not be before startDate");
        p.setProjectCode(r.code().trim());
        p.setProjectName(r.name().trim());
        p.setClientName(trim(r.client()));
        p.setOffering(trim(r.offering()));
        p.setProjectManager(trim(r.manager()));
        p.setStatus(codeOrNull(r.status()));
        p.setPriority(codeOrNull(r.priority()));
        p.setTeamSize(r.plannedTeamSize());
        p.setStartDate(r.startDate());
        p.setEndDate(r.endDate());
        p.setCompletionPercentage(r.completionPct() == null ? null
                : BigDecimal.valueOf(r.completionPct()).setScale(2, RoundingMode.HALF_UP));
        p.setHealthStatus(codeOrNull(r.health()));
        p.setProjectDescription(trim(r.description()));
        p.setActive(r.active() == null || r.active());
    }

    /** No token = allowed (open access, same as the rest of the dashboard); a logged-in EMPLOYEE is read-only. */
    private void requireWriter(String viewerEmail) {
        if (viewerEmail == null || viewerEmail.isBlank()) return;
        users.findByEmailIgnoreCase(viewerEmail)
                .filter(u -> "EMPLOYEE".equalsIgnoreCase(u.getRole()))
                .ifPresent(u -> { throw new ForbiddenException("Employees cannot change projects"); });
    }

    private DashProject find(Long id) {
        return projects.findById(id).orElseThrow(() -> new NotFoundException("Project", id));
    }

    /** % of start..end already passed, clamped to 0..100; null without both dates. */
    static Double timeElapsedPct(DashProject p) {
        if (p.getStartDate() == null || p.getEndDate() == null) return null;
        long total = ChronoUnit.DAYS.between(p.getStartDate(), p.getEndDate());
        if (total <= 0) return LocalDate.now().isBefore(p.getStartDate()) ? 0.0 : 100.0;
        long done = ChronoUnit.DAYS.between(p.getStartDate(), LocalDate.now());
        return round(Math.max(0, Math.min(100, 100.0 * done / total)));
    }

    static boolean isOverdue(DashProject p) {
        return !isClosed(p) && p.getEndDate() != null && p.getEndDate().isBefore(LocalDate.now());
    }

    private static boolean isBehindSchedule(DashProject p) {
        Double elapsed = timeElapsedPct(p);
        return !isClosed(p) && elapsed != null && p.getCompletionPercentage() != null
                && elapsed - p.getCompletionPercentage().doubleValue() >= BEHIND_SCHEDULE_GAP;
    }

    private static boolean isClosed(DashProject p) {
        return CLOSED_STATUS.contains(code(p.getStatus()))
                || (p.getCompletionPercentage() != null && p.getCompletionPercentage().compareTo(BigDecimal.valueOf(100)) >= 0);
    }

    private static boolean isActive(DashProject p) {
        return !Boolean.FALSE.equals(p.getActive());
    }

    private static Map<String, Integer> countBy(List<DashProject> ps, Function<DashProject, String> key) {
        Map<String, Integer> out = new TreeMap<>();
        ps.forEach(p -> out.merge(Optional.ofNullable(key.apply(p)).orElse("UNSET"), 1, Integer::sum));
        return out;
    }

    /** "In Progress" / "in-progress" -> IN_PROGRESS; null/blank -> "" (for comparisons). */
    private static String code(String v) {
        return v == null ? "" : v.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    private static String codeOrNull(String v) {
        return v == null || v.isBlank() ? null : code(v);
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static boolean contains(String v, String needle) {
        return v != null && v.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static double round(double d) {
        return Math.round(d * 10.0) / 10.0;
    }
}
