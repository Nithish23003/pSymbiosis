# Perf Insight: Backend (Spring Boot 3.5, Java 21)

This backend pulls together data from JIRA, Azure DevOps, Git (GitHub), a test management tool (TestRail) and SharePoint, plus exports and manual entries. It turns that data into **explainable, configurable, versioned** performance insights for each persona, project and offering. The insights support qualitative reviews; they don't replace them.

## Architecture

```
┌────────── cross-cutting: Azure AD roles · row-level access · audit log · schedulers ───────────┐
│ 1 INGEST     Connector SPI: LIVE | MOCK | FILE  →  raw_record (append-only, hash-deduplicated)  │
│              Jira · Azure DevOps · GitHub · TestRail · SharePoint(Graph delta) · CSV/JSON uploads │
│ 2 NORMALISE  JSON field-mapping per project (no code)  →  activity (canonical)                   │
│              + activity_override (manual edits: marked, who/when/why, never overwrites)          │
│ 3 RESOLVE    tool_account → associate (email / alias / fuzzy / manual) · unmatched report        │
│              effective-dated project_assignment → activity attributed to project+role ON THAT DAY│
│ 4 SCORE      versioned performance_model → cohort percentiles → score_result + score_component   │
│ 5 INSIGHT    rule-based anomaly flags  →  LLM plain-English summaries (grounded in the trace)    │
│ 6 REPORT     project view · associate view · monthly trends · drill-down to evidence             │
└────────────────────────────────────────────────────────────────────────────────────────────────┘
```

| Package | Responsibility |
|---|---|
| `security` | Azure AD JWT → roles, dev header auth, `AccessScope` (Employee = self, Manager = team, Service Head/Admin = all) |
| `org` | Offerings, teams, associates, projects, **effective-dated** assignments (Engineer / Senior Engineer / Lead) |
| `ingest` | Tool configs, connectors, mapping engine, incremental sync, scheduler, file import |
| `identity` | Cross-tool identity resolution, manual link/unlink, unmatched activity report |
| `activity` | Canonical activities, manual entries, overrides, attribution, drill-down queries |
| `notes` | Manager context notes (leave, onboarding, support duty), which remove days from the denominator |
| `model` | Versioned, documented performance models with persona / offering / project weight overrides |
| `scoring` | The single standard scoring method and its full calculation trace, plus monthly snapshots |
| `insight` | Anomaly / gaming detection, and AI summaries (OpenAI or Azure OpenAI, with a template fallback) |
| `report` | Project and associate reports, trends, explain and evidence endpoints |
| `audit` | Append-only audit log of every configuration change and manual change |
| `seed` | Demo dataset (acceptance criterion 11) |

## Run it

```bash
# 1. Database. Use your own, or: docker compose up -d
export DB_URL=jdbc:postgresql://localhost:5432/perfinsight DB_USERNAME=perfinsight DB_PASSWORD=perfinsight

# 2. Tokens (only needed for LIVE connections)
export JIRA_EMAIL=you@psiog.com JIRA_API_TOKEN=... GITHUB_TOKEN=...

# 3. Start with dev auth + demo data (EMPTY database)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,demo      # or: mvn spring-boot:run ...
```

* Swagger UI: http://localhost:8080/swagger-ui.html
* `requests.http` has a ready-made walkthrough for every acceptance criterion.
* The database settings are in `application.yml` under `spring.datasource`. They're blank placeholders that read `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`.
* The schema is created by Hibernate (`ddl-auto: update`) for hackathon speed. Move to Flyway before production.

> No Maven wrapper is committed. Run `mvn -N wrapper:wrapper` once, or use your IDE's Maven.

## Azure AD (Entra ID) setup

1. **App registration** for the API: *Expose an API*, then set the Application ID URI to `api://perf-insight` (or your own value, and set `AZURE_API_AUDIENCE` to match).
2. In the manifest, set `"requestedAccessTokenVersion": 2` so the tokens match `issuer-uri …/v2.0`.
3. Under **App roles**, create roles with these **values**: `Admin`, `ServiceHead`, `Manager`, `Employee`. If yours differ, edit `app.security.role-mapping`.
4. Assign users or groups to the roles under *Enterprise applications → Users and groups*.
5. Your frontend requests the scope `api://perf-insight/.default` and sends `Authorization: Bearer <token>`.
6. Users are matched to associates by the `oid` claim (`associate.azureObjectId`) or by the `preferred_username`, `email` or `upn` claims.

| Azure AD role | Sees | Can change |
|---|---|---|
| Employee | own data only | – |
| Manager | own team, plus people on projects they currently lead | manual entries, edits and context notes for their team |
| ServiceHead | everything | scoring runs, anomaly detection |
| Admin | everything | all configuration: org, tool configs, mappings, identity links, models |

## Acceptance criteria → where they live

| # | Criterion | Implementation |
|---|---|---|
| 1 | Connectors + exports, config-driven mappings, mock fallback | `ingest/connector/*`. Each project's tool config has a `mode` of LIVE, MOCK or FILE. MOCK files are shaped exactly like the real API responses, so switching to LIVE is a config change. Mappings are JSON (`resources/mappings/*.json`) and can be overridden per project through `PUT /api/admin/tool-configs/{id}/mapping`. CSV and JSON exports go to `POST …/{id}/import` |
| 2 | Manual entry and edits | `POST /api/activities/manual` (`origin=MANUAL`, with a required reason) and `POST /api/activities/{id}/overrides`. Source rows are never modified. Every edit keeps the original value, who made it, when, why and whether it's still active |
| 3 | Organisation mapping and mid-period moves | `ProjectAssignment(validFrom, validTo)` and `AttributionService` (the project and role held **on the event date**), plus `POST /api/admin/org/assignments/move`. Scoring splits people into person × project × role segments |
| 4 | Identity resolution | `IdentityResolutionService` matches by email, then alias, then username, then fuzzy name. Manual links override automatic matching. `GET /api/identity/unmatched` reports unmatched accounts and their activity |
| 5 | Configurable model, one standard method | `PerformanceModel` holds measures, weights and overrides (persona, offering, project) plus **required documentation and rationale** before it can be published. The method itself lives in `ScoringService` and is the same for everyone |
| 6 | Fair and explainable | Every result stores a component trace (raw value, pro-rated value, percentile, weight, contribution, exclusion reason and evidence IDs). The method also covers sparse data, partial periods and context notes, requires at least 3 measures from at least 2 tools, and caps each measure at 40% of the score |
| 7 | Reporting and drill-down | `/api/reports/projects/{id}`, `/associates/{id}`, `/…/trend`, `/scores/{id}/explain` and `/scores/{id}/evidence` |
| 8 | Sync and history | A watermark or delta cursor for each tool config, a scheduled sync (`app.sync.cron`), append-only raw records, immutable scoring runs stamped with the model version, and monthly snapshots |
| 9 | AI insights | `AnomalyService` runs 8 deterministic rules (self-merges, rubber-stamp reviews, PR splitting, point inflation and others). `AiInsightService` writes summaries grounded only in computed facts |
| 10 | Access | `AccessScopeService` applies to every report, activity, flag and AI endpoint. `/api/admin/**` is Admin only |
| 11 | Data | Profile `demo` seeds 3 offerings, 4 projects and 14 people. The data includes identity mismatches, two mid-period moves, leave, a new joiner, a client Jira that can only be reached through CSV export, and an engineer with patterns that look like gaming |

## The scoring method in brief

For each person × project × role segment:

1. Measures come from the effective activity data, which is source data plus any manual edits.
2. Count measures are pro-rated to 20 active working days. Days covered by context notes are removed first.
3. A measure with fewer data points than its `minSamples` is **excluded, not zeroed**.
4. Each measure becomes a **mid-rank percentile** within the peer cohort: the same role, optionally within the same offering. If the offering cohort is too small, the comparison falls back to the whole organisation. Measures where lower is better are flipped.
5. Weights start from the model's base weights, then apply overrides from least to most specific. They're renormalised over the measures that have data, and no single measure can exceed 40%.
6. A score is produced only when there are enough measures, tools and active days. Otherwise the result is `INSUFFICIENT_DATA` with the reasons.

Scores are **positions relative to peers**, grouped into bands: above, within or below the peer range. They aren't ratings.

## Going LIVE with your tokens

For Jira and GitHub, `requests.http` has request bodies for creating LIVE tool configs.

* **Jira Cloud** uses Basic auth (`JIRA_EMAIL` + `JIRA_API_TOKEN`) against `/rest/api/3/search/jql`. For Server or Data Center, set `settings.apiFlavour=SERVER` and `settings.authType=BEARER` to use a personal access token.
* **GitHub** needs a fine-grained token with read access to *Pull requests*, *Contents* and *Metadata*.
* **Azure DevOps** uses a personal access token (`ADO_PAT`) with *Work Items (Read)* scope.
* **SharePoint** uses an Azure AD app with the `Sites.Read.All` application permission. The client secret goes in `SHAREPOINT_CLIENT_SECRET`.
* **TestRail** uses a username and API key.

Set `mode` to `LIVE` on the tool config. Everything downstream stays the same.

## Live Jira: stats for one person

This works on its own, without the demo data or scoring. Set:

```bash
export JIRA_BASE_URL=https://your-domain.atlassian.net
export JIRA_EMAIL=you@psiog.com          # the account that owns the token
export JIRA_API_TOKEN=...                # https://id.atlassian.com/manage-profile/security/api-tokens
export JIRA_PROJECT_KEY=KEY              # optional default
```

```
GET /api/jira/connection                                   -> checks the token
GET /api/jira/projects                                     -> projects the token can see
GET /api/jira/users/search?name=nithish.kumar&projectKey=KEY
GET /api/jira/users/nithish.kumar/summary?projectKey=KEY&from=2026-06-01&to=2026-09-30
```

The summary endpoint returns:

| Field | Where it comes from |
|---|---|
| `user` | `/user/assignable/search` (people in the project), falling back to `/user/search`. The best match wins, in this order: email local part, then display name, then fuzzy. If the name is ambiguous, the response lists candidates and you pass `?accountId=` |
| `project.userIsAssignable` | Whether the person is actually in that project |
| `tickets` | JQL `project = KEY AND assignee = <accountId>`, with counts by status category, status and type |
| `storyPoints` | Totals for done, in progress and to do. The story points field is found automatically ("Story Points" / "Story point estimate"), or set `JIRA_STORY_POINTS_FIELDS` |
| `timeLogged` | JQL `worklogAuthor = <accountId>`, then each issue's worklogs, filtered to **his** entries within the dates. So this counts time he logged on anyone's ticket |
| `development` | Commits and PRs from Jira's **Development panel** (`/rest/dev-status`), both linked to his issues and authored by him |
| `issues` | One row per ticket, with points, his hours, commits, PRs and a link |

Commits and PRs only appear if Git is connected to Jira (for example with the *GitHub for Jira* app) and the commits, branches or PRs mention the issue key. If his Git name differs from his Jira name, add `&gitAliases=<github-login>`. The endpoint is open to Manager, Service Head and Admin roles.

## Tests

`mvn test` runs the unit tests for the scoring maths and the mapping engine.
