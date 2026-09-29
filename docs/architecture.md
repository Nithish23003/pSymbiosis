# System Architecture — Perf Insight

## Overview

Perf Insight is a **6-layer engineering performance intelligence platform** built for Psiog. It ingests raw activity data from developer toolchains (JIRA, GitHub, Azure DevOps, TestRail, SharePoint), normalises and attributes it, scores each engineer across five weighted dimensions, detects anti-gaming patterns, and generates AI-grounded narrative insights — all with a full audit trail and access controls via Azure AD.

---

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│          Cross-Cutting: Azure AD Roles · Row-Level Access · Audit Log           │
├─────────────────────────────────────────────────────────────────────────────────┤
│  1  INGEST       Connector SPI: LIVE | MOCK | FILE                              │
│                  Jira · Azure DevOps · GitHub · TestRail · SharePoint           │
│                  → raw_record (append-only, hash-deduplicated)                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│  2  NORMALISE    JSON field-mapping per project (no code)                       │
│                  → activity (canonical)                                         │
│                  + activity_override (manual edits — who/when/why, never lost)  │
├─────────────────────────────────────────────────────────────────────────────────┤
│  3  RESOLVE      tool_account → associate (email/alias/fuzzy/manual)            │
│                  effective-dated project_assignment → activity attributed to    │
│                  project + role on the exact event date                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│  4  SCORE        versioned performance_model → cohort percentiles               │
│                  → score_result + score_component (full calculation trace)      │
├─────────────────────────────────────────────────────────────────────────────────┤
│  5  INSIGHT      8 deterministic anomaly/gaming rules                           │
│                  → LLM plain-English summaries (grounded in computed facts)     │
├─────────────────────────────────────────────────────────────────────────────────┤
│  6  REPORT       project view · associate view · monthly trends · evidence      │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## Layer Descriptions

### Layer 1 — Ingest

| Mode | Description |
|------|-------------|
| **LIVE** | Direct API calls to tool providers (Jira Cloud/Server, GitHub REST, Azure DevOps REST, TestRail API, SharePoint Graph delta) |
| **MOCK** | Pre-shaped JSON files in `resources/mock/{tool}/` — identical shape to live API responses; switching to LIVE is a tool-config change, not a code change |
| **FILE** | CSV or JSON file import for tools accessible only via export (e.g. client-managed Jira) |

All raw records are **append-only** and hash-deduplicated — re-syncing is always safe.

Incremental sync uses a **watermark / delta cursor** per tool config. The default schedule is every 6 hours (`app.sync.cron`). The initial lookback window is configurable (`app.sync.initial-lookback-days`, default 180).

### Layer 2 — Normalise

Field mappings are stored as **JSON files per connector type** (`resources/mappings/{tool}.json`) and can be **overridden per project** through `PUT /api/admin/tool-configs/{id}/mapping`. Using JSONPath expressions, any custom field (e.g. `customfield_10028` for story points) maps to a canonical activity field without code changes.

Manual entries have `origin=MANUAL` and require a free-text `reason`. Overrides keep the original value, the editor's identity, the timestamp, and whether the override is still active.

### Layer 3 — Resolve

**Identity Resolution** maps tool accounts (GitHub handles, Jira usernames, ADO identities) to canonical associates using a priority chain:

```
1. Exact email match
2. Email-prefix / alias match
3. Display-name fuzzy match (Apache Commons Text similarity)
4. Manual link (Admin/Manager override)
```

**Attribution** uses effective-dated `project_assignment` records (`validFrom`, `validTo`). If an engineer moved projects mid-period, each activity is attributed to the project and role they held **on the event date** — not their current assignment.

### Layer 4 — Score

Scoring is a **single, documented, deterministic method** applied the same way to everyone:

1. Measures are drawn from effective (post-override) activity data.
2. Count-based measures are **pro-rated to 20 active working days**. Days covered by manager context notes (leave, on-call duty) are removed from the denominator first.
3. A measure with fewer data points than its `minSamples` is **excluded, not zeroed** — it cannot drag the score down due to sparse data.
4. Each measure becomes a **mid-rank percentile** within its cohort (same role, optionally same offering; falls back to org-wide if the cohort is too small).
5. Model weights start from the published base, then apply overrides from least-specific (persona) to most-specific (project). They are **renormalised** over measures with data; no single measure may exceed 40%.
6. A score is produced only when: ≥ 3 measures available, data from ≥ 2 distinct tools, ≥ `min-active-days` working days. Otherwise: `INSUFFICIENT_DATA`.

Scores are **peer-relative positions**, not absolute ratings.

### Layer 5 — Insight

**Deterministic anomaly rules (8):**
- Self-merge / self-approval detection
- Rubber-stamp code review (≤ threshold words, no substantive comments)
- Micro-commit bursting (commit:lines ratio anomaly)
- PR splitting (unusually high PR count relative to story points)
- Point inflation (suspiciously high story-point estimates)
- Ticket churning (excessive QA bounce count)
- Duplicate test execution runs
- Rapid-fire force-pushes

**AI Summaries:** When `app.ai.provider` is set to `openai` or `azure-openai`, the `AiInsightService` generates narrative summaries grounded only in the computed facts from the scoring trace. A template fallback is available when no AI provider is configured.

### Layer 6 — Report

| Endpoint | Description |
|----------|-------------|
| `GET /api/reports/projects/{id}` | Project-level performance summary |
| `GET /api/reports/associates/{id}` | Individual engineer report |
| `GET /api/reports/associates/{id}/trend` | Monthly trend data |
| `GET /api/scores/{id}/explain` | Full calculation trace with raw values, percentiles, and weights |
| `GET /api/scores/{id}/evidence` | Drill-down to source activity records |

---

## Package Map

| Package | Responsibility |
|---------|---------------|
| `security` | Azure AD JWT validation → internal roles, dev-mode header auth, `AccessScope` (Employee = self, Manager = team, ServiceHead/Admin = all) |
| `org` | Offerings, teams, associates, projects, effective-dated assignments |
| `ingest` | Tool configs, connector SPI (LIVE/MOCK/FILE), mapping engine, incremental sync, scheduler, file import |
| `identity` | Cross-tool identity resolution, manual link/unlink, unmatched activity report |
| `activity` | Canonical activities, manual entries, overrides, attribution, drill-down queries |
| `notes` | Manager context notes (leave, onboarding, on-call duty) — removes days from the scoring denominator |
| `model` | Versioned performance models with required rationale documentation |
| `scoring` | Single standard scoring method + full calculation trace, monthly snapshot scheduler |
| `insight` | Anomaly/gaming detection rules, AI summary generation |
| `report` | Project and associate reports, trend APIs |
| `audit` | Append-only audit log of every configuration and manual change |
| `seed` | Demo dataset (3 offerings, 4 projects, 14 associates) |

---

## Deployment Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  Client (Browser)                                                │
│  React 18 + TypeScript + Vite                                    │
│  Authorization: Bearer <Azure AD token>                          │
└───────────────────────────────┬──────────────────────────────────┘
                                │ HTTPS
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│  API Server — Spring Boot 3.5 (Java 17)                          │
│  Port 8080                                                       │
│  OAuth2 Resource Server (Azure AD Entra ID JWT)                  │
│  Swagger UI: /swagger-ui.html                                    │
└──────┬────────────────────────────────────────┬──────────────────┘
       │                                        │
       ▼                                        ▼
┌──────────────┐                   ┌────────────────────────────────┐
│  Database    │                   │  External Tool APIs            │
│  MySQL /     │                   │  • Jira Cloud (Basic Auth)     │
│  PostgreSQL  │                   │  • GitHub REST (PAT)           │
└──────────────┘                   │  • Azure DevOps (PAT)          │
                                   │  • TestRail (API Key)          │
                                   │  • SharePoint Graph (OAuth2)   │
                                   │  • OpenAI / Azure OpenAI       │
                                   └────────────────────────────────┘
```

---

## Security Model

| Azure AD Role | Data Visibility | Write Permissions |
|---------------|----------------|-------------------|
| `Employee` | Own data only | None |
| `Manager` | Own team + projects they lead | Manual entries, overrides, context notes for their team |
| `ServiceHead` | Organisation-wide | Trigger scoring runs, anomaly detection |
| `Admin` | Organisation-wide | All configuration (org, tool configs, mappings, identity, models) |

All API calls go through `AccessScopeService`, which enforces row-level access. `/api/admin/**` is restricted to the `Admin` role.

---

## Technology Stack

| Concern | Technology |
|---------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.5.6 |
| Database | MySQL (primary) / PostgreSQL (supported) |
| ORM | Spring Data JPA / Hibernate |
| Security | Spring Security + OAuth2 Resource Server |
| Identity Provider | Azure AD (Entra ID) |
| API Docs | SpringDoc / Swagger UI |
| JSON Path | Jayway JsonPath |
| Data Processing | Apache Commons CSV, Commons Math3, Commons Text |
| AI Provider | OpenAI / Azure OpenAI (configurable; template fallback) |
| Containerisation | Docker Compose |
| Build | Maven |
