# System Architecture — Perf Insight

## Overview

Perf Insight is a **6-layer engineering performance intelligence platform** built for Psiog. It ingests raw activity data from developer toolchains (JIRA, GitHub, Azure DevOps, TestRail, SharePoint), normalises and attributes it, scores each engineer across five weighted dimensions, detects anti-gaming patterns, and generates AI-grounded narrative insights — all with a full audit trail and role-based access via Azure AD.

---

## 6-Layer Pipeline

```mermaid
flowchart TD
    subgraph EXT["External Sources"]
        J[("Jira\nCloud / Server")]
        G[("GitHub\nREST API")]
        A[("Azure DevOps\nBoards")]
        T[("TestRail\nAPI")]
        S[("SharePoint\nGraph API")]
        F[("CSV / JSON\nFile Export")]
    end

    subgraph L1["Layer 1 · INGEST"]
        direction LR
        C1["JiraConnector"]
        C2["GitHubConnector"]
        C3["AzureDevOpsConnector"]
        C4["TestRailConnector"]
        C5["SharePointConnector"]
        CI["FileImportService"]
        RR[("raw_record\nappend-only\nhash-dedup")]
    end

    subgraph L2["Layer 2 · NORMALISE"]
        ME["MappingEngine\nJSONPath field mapping\nper project, no code"]
        AO["activity_override\nhuman edits · who/when/why\nnever overwrites source"]
        ACT[("activity\ncanonical")]
    end

    subgraph L3["Layer 3 · RESOLVE"]
        IR["IdentityResolutionService\nemail → alias → fuzzy → manual"]
        AT["AttributionService\nevent date → ProjectAssignment\n→ project + role on that day"]
    end

    subgraph L4["Layer 4 · SCORE"]
        SC["ScoringService\nversioned model · cohort percentiles\npro-rated · exclusions · trace"]
        SR[("score_result\n+ score_component\nfull calc trace")]
    end

    subgraph L5["Layer 5 · INSIGHT"]
        AN["AnomalyService\n8 deterministic rules\ngaming detection"]
        AI["AiInsightService\nLLM summaries\ngrounded in facts"]
    end

    subgraph L6["Layer 6 · REPORT"]
        RP["ReportController\nproject · associate · trend\nexplain · evidence"]
        DB["DashboardController\noverview · leaderboard\ndeveloper detail"]
    end

    J & G & A & T & S --> L1
    F --> CI
    C1 & C2 & C3 & C4 & C5 & CI --> RR
    RR --> ME
    ME --> ACT
    AO -.->|"layered at\nread-time"| ACT
    ACT --> IR
    IR --> AT
    AT --> SC
    SC --> SR
    SR --> AN
    SR --> AI
    SR --> RP
    SR --> DB

    style EXT fill:#f5f5f5,stroke:#bbb
    style L1 fill:#e8f4fd,stroke:#90caf9
    style L2 fill:#e8f5e9,stroke:#a5d6a7
    style L3 fill:#fff3e0,stroke:#ffcc80
    style L4 fill:#f3e5f5,stroke:#ce93d8
    style L5 fill:#fce4ec,stroke:#f48fb1
    style L6 fill:#e0f2f1,stroke:#80cbc4
```

---

## Deployment Architecture

```mermaid
flowchart LR
    subgraph Browser["Browser — React 18 + TypeScript"]
        UI["Perf Insight UI\nlocalhost:5173"]
    end

    subgraph API["API Server — Spring Boot 3.5 · Java 17 · :8080"]
        SEC["OAuth2 Resource Server\nAzure AD JWT validation"]
        CTRL["Controllers\nREST endpoints"]
        SVC["Services\nBusiness Logic"]
        SCHED["Schedulers\nSync every 6h\nSnapshot on 1st"]
    end

    subgraph DB["Database"]
        MYSQL[("MySQL 8\nPerfromance_Platform")]
    end

    subgraph TOOLS["External Tool APIs"]
        JIRA["Jira Cloud"]
        GH["GitHub"]
        ADO["Azure DevOps"]
        TR["TestRail"]
        SP["SharePoint\n(Graph API)"]
    end

    subgraph AI["AI Provider (optional)"]
        OAI["OpenAI\nor Azure OpenAI"]
    end

    subgraph AAD["Azure AD · Entra ID"]
        IDP["JWT Issuer\nApp Roles\nUser Identity"]
    end

    UI -->|"HTTPS\nBearer token"| SEC
    AAD -->|"validates"| SEC
    SEC --> CTRL
    CTRL --> SVC
    SVC --> MYSQL
    SCHED --> TOOLS
    SVC --> OAI

    style Browser fill:#e3f2fd,stroke:#90caf9
    style API fill:#f3e5f5,stroke:#ce93d8
    style DB fill:#fff3e0,stroke:#ffcc80
    style TOOLS fill:#e8f5e9,stroke:#a5d6a7
    style AI fill:#fce4ec,stroke:#f48fb1
    style AAD fill:#e0f2f1,stroke:#80cbc4
```

---

## Security & Access Control

```mermaid
flowchart TD
    JWT["Azure AD JWT Token\n'roles' claim"]

    JWT --> CONV["AzureAdJwtConverter\nmaps role values → AppRole enum"]

    CONV --> R1["EMPLOYEE\nSelf data only"]
    CONV --> R2["MANAGER\nOwn team + led projects\nManual entries & notes"]
    CONV --> R3["SERVICE_HEAD\nOrg-wide read\nScoring & anomaly triggers"]
    CONV --> R4["ADMIN\nFull access\n/api/admin/**"]

    R1 & R2 & R3 & R4 --> ASC["AccessScopeService\nRow-level enforcement\non every report & activity"]

    style JWT fill:#fff3e0,stroke:#ffcc80
    style CONV fill:#f3e5f5,stroke:#ce93d8
    style R1 fill:#e8f5e9,stroke:#a5d6a7
    style R2 fill:#e3f2fd,stroke:#90caf9
    style R3 fill:#fce4ec,stroke:#f48fb1
    style R4 fill:#ffebee,stroke:#ef9a9a
    style ASC fill:#e0f2f1,stroke:#80cbc4
```

---

## Scoring Algorithm Flow

```mermaid
flowchart TD
    IN["Associate + Period + Model"]

    IN --> SEG["Tenure Segmentation\nattributionEngine\nproject × role × date segments"]
    SEG --> CTX["Context Adjustments\nManagerContextNotes\nremove leave/on-call days\nfrom denominator"]
    CTX --> FILT["Activity Filtering\nperiod window · identity handles\nmanual entries"]

    FILT --> D1["Delivery\n30%\nstory points + merged PRs\nvs persona benchmark"]
    FILT --> D2["Quality\n25%\nbug leak rate\nrework bounce count"]
    FILT --> D3["Review\n20%\nsubstantive reviews\nsuperficial filtered out"]
    FILT --> D4["Documentation\n15%\nSharePoint docs\nword count bonus"]
    FILT --> D5["Reliability\n10%\non-call bonus\ndeployment stability"]

    D1 & D2 & D3 & D4 & D5 --> WCS["Weighted Composite Score\n0 – 100"]

    WCS --> BAND["Rating Band\n≥90 Exceeding\n≥80 Strong\n≥68 Meeting\n<68 Developing"]
    WCS --> AGD["Anti-Gaming Detection\n8 deterministic rules\nflags only — no auto-penalty"]
    WCS --> AIN["AI Narrative\nexecutive summary\nstrengths · growth areas"]

    style IN fill:#e3f2fd,stroke:#90caf9
    style WCS fill:#f3e5f5,stroke:#ce93d8
    style BAND fill:#e8f5e9,stroke:#a5d6a7
    style AGD fill:#fce4ec,stroke:#f48fb1
    style AIN fill:#fff3e0,stroke:#ffcc80
```

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
| `dashboard` | Simplified KPI overview, leaderboard, developer detail views |
| `audit` | Append-only audit log of every configuration and manual change |
| `seed` | Demo dataset (3 offerings, 4 projects, 14 associates) |

---

## Technology Stack

| Concern | Technology |
|---------|-----------|
| Language | Java 17 |
| Framework | Spring Boot 3.5.6 |
| Database | MySQL 8 (primary) / PostgreSQL 16 (docker-compose) |
| ORM | Spring Data JPA / Hibernate (`ddl-auto: update` → use Flyway in prod) |
| Security | Spring Security + OAuth2 Resource Server |
| Identity Provider | Azure AD (Entra ID) |
| API Docs | SpringDoc / Swagger UI (`/swagger-ui.html`) |
| JSON Path | Jayway JsonPath |
| Data Processing | Apache Commons CSV, Commons Math3, Commons Text |
| AI Provider | OpenAI / Azure OpenAI (configurable; template fallback when `provider=none`) |
| Containerisation | Docker Compose |
| Build | Maven 3.9+ |
