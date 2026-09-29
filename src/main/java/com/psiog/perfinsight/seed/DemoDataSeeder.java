package com.psiog.perfinsight.seed;

import com.psiog.perfinsight.activity.*;
import com.psiog.perfinsight.config.AppProperties;
import com.psiog.perfinsight.identity.AssociateAlias;
import com.psiog.perfinsight.identity.AssociateAliasRepository;
import com.psiog.perfinsight.ingest.*;
import com.psiog.perfinsight.insight.AnomalyService;
import com.psiog.perfinsight.model.CohortScope;
import com.psiog.perfinsight.model.ModelDtos.*;
import com.psiog.perfinsight.model.ModelService;
import com.psiog.perfinsight.notes.ContextNote;
import com.psiog.perfinsight.notes.ContextNoteRepository;
import com.psiog.perfinsight.notes.ContextNoteType;
import com.psiog.perfinsight.org.*;
import com.psiog.perfinsight.scoring.ScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Profile "demo" (app.demo.seed=true): builds a complete, realistic dataset on an EMPTY database -
 * 3 offerings, 4 projects, 14 people, mid-period moves, identity mismatches, context notes, manual data,
 * MOCK tool connections, two published model versions and monthly snapshots.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.demo", name = "seed", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private final AppProperties props;
    private final OfferingRepository offerings;
    private final TeamRepository teams;
    private final AssociateRepository associates;
    private final ProjectRepository projects;
    private final ProjectAssignmentRepository assignments;
    private final AssociateAliasRepository aliases;
    private final ContextNoteRepository notes;
    private final ProjectToolConfigRepository toolConfigs;
    private final SyncService sync;
    private final FileImportService fileImport;
    private final ManualDataService manual;
    private final ActivityRepository activities;
    private final ModelService models;
    private final ScoringService scoring;
    private final AnomalyService anomalies;

    private final Map<String, Associate> people = new HashMap<>();
    private final Map<String, Project> projectByCode = new HashMap<>();

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (offerings.count() > 0) {
            log.info("Demo seed skipped - database already has data");
            return;
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "demo-seed", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            log.info("Seeding demo data ...");
            seedOrg();
            seedToolConfigsAndSync();
            seedManualData();
            seedModelsAndScores();
            log.info("Demo seed complete. Try GET /api/reports/projects/{id}?from={}&to={}",
                    props.getDemo().getPeriodStart(), props.getDemo().getPeriodEnd());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------------------------------------
    private void seedOrg() {
        Offering de = offerings.save(new Offering("DE", "Digital Engineering"));
        Offering qe = offerings.save(new Offering("QE", "Quality Engineering"));
        Offering dai = offerings.save(new Offering("DAI", "Data & AI"));

        person("priya", "PSG001", "Priya Sharma");
        person("arjun", "PSG002", "Arjun Mehta");
        person("kavya", "PSG003", "Kavya Reddy");
        person("rahul", "PSG004", "Rahul Verma");
        person("sneha", "PSG005", "Sneha Iyer");
        person("vikram", "PSG006", "Vikram Nair");
        person("ananya", "PSG007", "Ananya Gupta");
        person("karthik", "PSG008", "Karthik Subramanian");
        person("divya", "PSG009", "Divya Menon");
        person("suresh", "PSG010", "Suresh Babu");
        person("meera", "PSG011", "Meera Pillai");
        person("aditya", "PSG012", "Aditya Rao");
        person("lakshmi", "PSG013", "Lakshmi Narayanan");
        person("rohan", "PSG014", "Rohan Das");

        Team phoenix = team("Team Phoenix", de, "priya", "priya", "rahul", "sneha", "vikram", "ananya", "karthik", "lakshmi", "rohan");
        Team orion = team("Team Orion", qe, "arjun", "arjun", "divya", "suresh");
        Team nova = team("Team Nova", dai, "kavya", "kavya", "meera", "aditya");
        log.info("Teams: {}, {}, {}", phoenix.getName(), orion.getName(), nova.getName());

        project("PAY", "Payments Platform", de);
        project("CLM", "Claims Portal", de);
        project("QAX", "Quality Automation Hub", qe);
        project("INS", "Insights Lakehouse", dai);

        LocalDate s = LocalDate.of(2026, 1, 1);
        LocalDate moveDay = LocalDate.of(2026, 8, 1);
        assign("priya", "PAY", Persona.LEAD, s, null);
        assign("arjun", "QAX", Persona.LEAD, s, null);
        assign("kavya", "INS", Persona.LEAD, s, null);
        // promotion + project move mid-period
        assign("rahul", "PAY", Persona.SENIOR_ENGINEER, s, moveDay.minusDays(1));
        assign("rahul", "CLM", Persona.LEAD, moveDay, null);
        assign("sneha", "PAY", Persona.ENGINEER, s, null);
        // project move mid-period
        assign("vikram", "PAY", Persona.ENGINEER, s, moveDay.minusDays(1));
        assign("vikram", "CLM", Persona.ENGINEER, moveDay, null);
        assign("ananya", "CLM", Persona.SENIOR_ENGINEER, s, null);
        assign("karthik", "CLM", Persona.ENGINEER, s, null);
        assign("divya", "QAX", Persona.ENGINEER, s, null);
        assign("suresh", "QAX", Persona.SENIOR_ENGINEER, s, null);
        assign("meera", "INS", Persona.ENGINEER, LocalDate.of(2026, 7, 15), null);   // joined mid-period
        assign("aditya", "INS", Persona.SENIOR_ENGINEER, s, null);
        assign("lakshmi", "CLM", Persona.ENGINEER, s, null);
        assign("rohan", "PAY", Persona.ENGINEER, s, null);

        // known alias -> AUTO_ALIAS match; Karthik's GitHub account is deliberately left for manual linking
        aliases.save(new AssociateAlias(people.get("rohan"), ToolType.GIT, "rk-dev"));

        note("lakshmi", "CLM", ContextNoteType.LEAVE, "2026-08-10", "2026-08-21", "Planned annual leave", true);
        note("meera", null, ContextNoteType.ONBOARDING, "2026-07-15", "2026-08-04", "New joiner - onboarding and environment setup", true);
        note("suresh", null, ContextNoteType.SUPPORT_DUTY, "2026-09-01", "2026-09-11", "Production support rota for the QE release", true);
        note("rahul", "CLM", ContextNoteType.OTHER, "2026-08-01", "2026-08-07", "Transition week into the Lead role on CLM", false);
    }

    private void seedToolConfigsAndSync() throws Exception {
        tool("PAY", ToolType.JIRA, "Jira - PAY", Map.of("projectKey", "PAY", "browseUrl", "https://psiog.atlassian.net", "mockFile", "mock/jira/PAY.json"), "jira");
        tool("PAY", ToolType.GIT, "GitHub - payments-api", Map.of("owner", "psiog", "repo", "payments-api", "mockFile", "mock/github/payments-api.json"), "github");
        tool("PAY", ToolType.TEST_MANAGEMENT, "TestRail - PAY", Map.of("projectId", "1", "mockFile", "mock/testmgmt/PAY.json"), "testrail");
        tool("PAY", ToolType.SHAREPOINT, "SharePoint - PAY", Map.of("siteId", "psiog.sharepoint.com,pay", "mockFile", "mock/sharepoint/PAY.json"), "sharepoint");

        tool("CLM", ToolType.AZURE_DEVOPS, "Azure Boards - Claims", Map.of("organization", "psiog", "project", "Claims", "mockFile", "mock/ado/CLM.json"), "ado");
        tool("CLM", ToolType.GIT, "GitHub - claims-portal", Map.of("owner", "psiog", "repo", "claims-portal", "mockFile", "mock/github/claims-portal.json"), "github");
        tool("CLM", ToolType.TEST_MANAGEMENT, "TestRail - CLM", Map.of("projectId", "2", "mockFile", "mock/testmgmt/CLM.json"), "testrail");
        tool("CLM", ToolType.SHAREPOINT, "SharePoint - CLM", Map.of("siteId", "psiog.sharepoint.com,clm", "mockFile", "mock/sharepoint/CLM.json"), "sharepoint");

        tool("QAX", ToolType.JIRA, "Jira - QAX", Map.of("projectKey", "QAX", "browseUrl", "https://psiog.atlassian.net", "mockFile", "mock/jira/QAX.json"), "jira");
        tool("QAX", ToolType.GIT, "GitHub - qa-automation", Map.of("owner", "psiog", "repo", "qa-automation", "mockFile", "mock/github/qa-automation.json"), "github");
        tool("QAX", ToolType.TEST_MANAGEMENT, "TestRail - QAX", Map.of("projectId", "3", "mockFile", "mock/testmgmt/QAX.json"), "testrail");
        tool("QAX", ToolType.SHAREPOINT, "SharePoint - QAX", Map.of("siteId", "psiog.sharepoint.com,qax", "mockFile", "mock/sharepoint/QAX.json"), "sharepoint");

        // INS: client-hosted Jira that we cannot reach -> FILE mode, fed by CSV exports
        ProjectToolConfig insJira = toolWithMode("INS", ToolType.JIRA, ConnectionMode.FILE, "Jira (client-hosted) - CSV export",
                Map.of("csvIdColumn", "Issue key", "browseUrl", "https://jira.client.example"), "jira");
        tool("INS", ToolType.GIT, "GitHub - insights-lakehouse", Map.of("owner", "psiog", "repo", "insights-lakehouse", "mockFile", "mock/github/insights-lakehouse.json"), "github");
        tool("INS", ToolType.SHAREPOINT, "SharePoint - INS", Map.of("siteId", "psiog.sharepoint.com,ins", "mockFile", "mock/sharepoint/INS.json"), "sharepoint");

        sync.syncAll("DEMO_SEED");
        try (InputStream in = new ClassPathResource("mock/jira/INS_export.csv").getInputStream()) {
            fileImport.importStream(insJira.getId(), "INS_export.csv", in);
        }
    }

    private void seedManualData() {
        Project ins = projectByCode.get("INS");
        manual.createManual(new ActivityDtos.ManualActivityRequest(people.get("kavya").getId(), ins.getId(), ActivityType.DOCUMENT,
                "Architecture review pack - lakehouse security (client Confluence)", null,
                LocalDate.of(2026, 7, 8).atTime(11, 0).toInstant(ZoneOffset.UTC), Map.of("action", "created"),
                "Written in the client's Confluence, which we cannot integrate"));
        manual.createManual(new ActivityDtos.ManualActivityRequest(people.get("aditya").getId(), ins.getId(), ActivityType.TICKET,
                "Client data migration cut-over (tracked in client ServiceNow)", null,
                LocalDate.of(2026, 8, 19).atTime(16, 0).toInstant(ZoneOffset.UTC),
                Map.of("completed", true, "storyPoints", 5, "cycleTimeHours", 72),
                "Work tracked in client ServiceNow - no integration available"));
        manual.createManual(new ActivityDtos.ManualActivityRequest(people.get("meera").getId(), ins.getId(), ActivityType.TEST_RUN,
                "UAT support session with client analysts", null,
                LocalDate.of(2026, 9, 9).atTime(10, 0).toInstant(ZoneOffset.UTC), Map.of("passed", true),
                "UAT run in client's own tooling"));

        // an edit on top of source data: re-estimated story points (source value stays visible)
        activities.search(props.getDemo().getPeriodStart(), props.getDemo().getPeriodEnd(), people.get("sneha").getId(),
                        projectByCode.get("PAY").getId(), ActivityType.TICKET, PageRequest.of(0, 1))
                .stream().findFirst().ifPresent(a -> manual.addOverride(a.getId(),
                        new ActivityDtos.OverrideRequest("attributes.storyPoints", "3", "Re-estimated in sprint review - original estimate was a typo")));
    }

    private void seedModelsAndScores() {
        ModelDto v1 = models.createDraft(new ModelRequest("default", "Balanced Engineering Model",
                """
                GOOD performance = reliably delivering planned work (throughput and cycle time), with low rework \
                (reopen rate), contributing to others' work (code reviews), testing what ships and sharing knowledge.
                POOR performance = consistently low delivery AND low collaboration relative to peers in the same role, \
                sustained over time - never a single month or a single measure.
                A score is a percentile position versus peers in the same role, not a rating. It opens a conversation; \
                it does not replace one.""",
                """
                Eight measures across four tools so no single activity count dominates; each is capped at 40% of the score. \
                Delivery carries the most weight for Engineers; Leads are weighted towards reviews and documentation \
                because that is how they multiply the team. Quality Engineering is weighted towards test execution and \
                defects found. Test executions are switched off for INS because that project has no test tool. \
                Count measures are pro-rated per 20 active days so leave, onboarding and partial periods are fair.""",
                "Initial version", CohortScope.PERSONA, 3, 2,
                List.of(new MeasureRequest("delivery.throughput_points", 25, 3, true, "Primary outcome: planned work delivered"),
                        new MeasureRequest("delivery.cycle_time_days", 15, 3, true, "Flow efficiency - lower is better"),
                        new MeasureRequest("quality.reopen_rate", 10, 4, true, "Rework signal - lower is better"),
                        new MeasureRequest("code.prs_merged", 10, 2, true, "Code delivered; low weight to avoid PR-splitting incentives"),
                        new MeasureRequest("collab.reviews_given", 15, 2, true, "Helping others ship"),
                        new MeasureRequest("quality.test_executions", 10, 5, true, "Testing what ships"),
                        new MeasureRequest("quality.defects_found", 5, 2, true, "Defects caught before release"),
                        new MeasureRequest("knowledge.doc_contributions", 10, 1, true, "Knowledge sharing")),
                List.of(new OverrideRequest("collab.reviews_given", Persona.LEAD, null, null, 25, "Leads multiply the team through reviews"),
                        new OverrideRequest("knowledge.doc_contributions", Persona.LEAD, null, null, 15, "Leads own design docs / ADRs"),
                        new OverrideRequest("delivery.throughput_points", Persona.LEAD, null, null, 15, "Leads carry less ticket work"),
                        new OverrideRequest("quality.test_executions", null, offeringId("QE"), null, 30, "Core output for Quality Engineering"),
                        new OverrideRequest("quality.defects_found", null, offeringId("QE"), null, 15, "Core output for Quality Engineering"),
                        new OverrideRequest("code.prs_merged", null, offeringId("QE"), null, 5, "Automation code is secondary for QE"),
                        new OverrideRequest("quality.test_executions", null, null, projectByCode.get("INS").getId(), 0, "INS has no test management tool"))));
        models.publish(v1.id());

        LocalDate start = props.getDemo().getPeriodStart();
        LocalDate end = props.getDemo().getPeriodEnd();
        YearMonth v2From = YearMonth.of(2026, 9);

        // monthly snapshots under v1 (frozen history for trends)
        for (YearMonth ym = YearMonth.from(start); ym.isBefore(v2From); ym = ym.plusMonths(1)) {
            LocalDate ms = ym.atDay(1), me = min(ym.atEndOfMonth(), LocalDate.now());
            scoring.snapshotFor(ms, me);
            anomalies.detect(ms, me);
        }

        // v2: team decided reviews matter more for Seniors too - past months keep their v1 results
        ModelDto draft = models.newVersion(v1.id(), "Seniors weighted towards reviews; peer cohort by role within offering");
        List<OverrideRequest> ov = new ArrayList<>(draft.overrides().stream()
                .map(o -> new OverrideRequest(o.measureKey(), o.persona(), o.offeringId(), o.projectId(), o.weight(), o.reasoning()))
                .toList());
        ov.add(new OverrideRequest("collab.reviews_given", Persona.SENIOR_ENGINEER, null, null, 20, "Seniors are expected to review more"));
        models.updateDraft(draft.id(), new ModelRequest("default", draft.name(), draft.documentation(), draft.rationale(),
                draft.changeNote(), CohortScope.PERSONA_OFFERING, draft.minMeasures(), draft.minTools(),
                draft.measures().stream().map(m -> new MeasureRequest(m.measureKey(), m.weight(), m.minSamples(), m.enabled(), m.reasoning())).toList(),
                ov));
        models.publish(draft.id());

        for (YearMonth ym = v2From; !ym.isAfter(YearMonth.from(end)); ym = ym.plusMonths(1)) {
            LocalDate ms = ym.atDay(1), me = min(ym.atEndOfMonth(), LocalDate.now());
            if (me.isBefore(ms)) break;
            scoring.snapshotFor(ms, me);
            anomalies.detect(ms, me);
        }
        scoring.run(start, end, null, "ON_DEMAND");
        anomalies.detect(start, end);
    }

    // ---- helpers ------------------------------------------------------------------------------------
    private void person(String key, String code, String name) {
        Associate a = new Associate();
        a.setEmployeeCode(code);
        a.setFullName(name);
        a.setEmail(name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@psiog.com");
        people.put(key, associates.save(a));
    }

    private Team team(String name, Offering o, String manager, String... members) {
        Team t = new Team();
        t.setName(name);
        t.setOffering(o);
        t.setManager(people.get(manager));
        teams.save(t);
        for (String m : members) {
            Associate a = people.get(m);
            a.setTeam(t);
            associates.save(a);
        }
        return t;
    }

    private void project(String code, String name, Offering o) {
        Project p = new Project();
        p.setCode(code);
        p.setName(name);
        p.setOffering(o);
        projectByCode.put(code, projects.save(p));
    }

    private void assign(String who, String project, Persona persona, LocalDate from, LocalDate to) {
        ProjectAssignment pa = new ProjectAssignment();
        pa.setAssociate(people.get(who));
        pa.setProject(projectByCode.get(project));
        pa.setPersona(persona);
        pa.setValidFrom(from);
        pa.setValidTo(to);
        pa.setAllocationPercent(100);
        assignments.save(pa);
    }

    private void note(String who, String project, ContextNoteType type, String from, String to, String text, boolean exclude) {
        ContextNote n = new ContextNote();
        n.setAssociate(people.get(who));
        n.setProject(project == null ? null : projectByCode.get(project));
        n.setType(type);
        n.setStartDate(LocalDate.parse(from));
        n.setEndDate(LocalDate.parse(to));
        n.setNote(text);
        n.setExcludeFromActiveDays(exclude);
        notes.save(n);
    }

    private ProjectToolConfig tool(String project, ToolType type, String name, Map<String, Object> settings, String cred) {
        return toolWithMode(project, type, ConnectionMode.MOCK, name, settings, cred);
    }

    private ProjectToolConfig toolWithMode(String project, ToolType type, ConnectionMode mode, String name,
                                           Map<String, Object> settings, String cred) {
        ProjectToolConfig c = new ProjectToolConfig();
        c.setProject(projectByCode.get(project));
        c.setToolType(type);
        c.setMode(mode);
        c.setName(name);
        c.setCredentialRef(cred);
        c.setSettings(new HashMap<>(settings));
        return toolConfigs.save(c);
    }

    private Long offeringId(String code) {
        return offerings.findByCode(code).map(Offering::getId).orElseThrow();
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
