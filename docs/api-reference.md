# API Reference — Perf Insight

Base URL: `http://localhost:8080` (development)  
API Docs (Swagger UI): `http://localhost:8080/swagger-ui.html`  
Auth: All endpoints require `Authorization: Bearer <Azure AD JWT>` unless `app.security.open-access=true` (development only).

---

## Authentication & Roles

| Role | Description |
|------|-------------|
| `EMPLOYEE` | Can access own performance data only |
| `MANAGER` | Can access team data and projects they lead; can write manual entries and context notes for their team |
| `SERVICE_HEAD` | Organisation-wide read access; can trigger scoring and anomaly detection runs |
| `ADMIN` | Full access including all configuration endpoints (`/api/admin/**`) |

---

## Jira Integration

### `GET /api/jira/connection`
Verifies that the configured Jira credentials are valid.

**Roles:** Manager, ServiceHead, Admin  
**Response:** `{ "connected": true, "baseUrl": "...", "authenticatedUser": "..." }`

---

### `GET /api/jira/projects`
Lists all Jira projects visible to the configured API token.

**Roles:** Manager, ServiceHead, Admin  
**Response:** Array of `{ "key": "SCRUM", "name": "...", "lead": "..." }`

---

### `GET /api/jira/users/search`
Searches for Jira users in a project by display name or email.

**Roles:** Manager, ServiceHead, Admin  
**Query Params:**
| Param | Required | Description |
|-------|----------|-------------|
| `name` | Yes | Display name or email prefix to search |
| `projectKey` | No | Filter to users assignable in this project |

**Response:** Array of `{ "accountId": "...", "displayName": "...", "email": "..." }`

---

### `GET /api/jira/users/{name}/summary`
Full Jira activity summary for one person — tickets, story points, time logged, commits, PRs.

**Roles:** Manager, ServiceHead, Admin  
**Path Params:** `name` — display name, email local part, or Jira username  
**Query Params:**
| Param | Required | Description |
|-------|----------|-------------|
| `projectKey` | No | Jira project key (defaults to `app.jira.default-project-key`) |
| `from` | No | ISO date (e.g. `2026-06-01`) |
| `to` | No | ISO date (e.g. `2026-09-30`) |
| `accountId` | No | Disambiguate when name matches multiple users |
| `gitAliases` | No | Comma-separated GitHub login names for development panel lookup |

**Response:**
```json
{
  "user": { "accountId": "...", "displayName": "...", "email": "..." },
  "project": { "key": "SCRUM", "name": "...", "userIsAssignable": true },
  "tickets": {
    "total": 42,
    "byStatus": { "Done": 38, "In Progress": 4 },
    "byType": { "Story": 30, "Bug": 8, "Spike": 4 }
  },
  "storyPoints": { "done": 145, "inProgress": 12, "toDo": 8 },
  "timeLogged": { "hours": 320.5, "issues": 15 },
  "development": { "commits": 87, "pullRequests": 14 },
  "issues": [ { "key": "SCRUM-101", "summary": "...", "points": 5, ... } ]
}
```

---

## Organisation

### `GET /api/org/associates`
Lists all associates (paginated). Filtered by role scope.

**Roles:** Manager (own team), ServiceHead, Admin  
**Query Params:** `page`, `size`, `offeringId`, `projectId`

---

### `GET /api/org/associates/{id}`
Returns a single associate's profile.

**Roles:** Employee (own), Manager (team), ServiceHead, Admin

---

### `GET /api/org/projects`
Lists all projects.

**Roles:** All authenticated users

---

### `GET /api/org/projects/{id}`
Returns project details.

**Roles:** All authenticated users

---

### `GET /api/org/assignments`
Lists effective-dated project role assignments.

**Query Params:** `associateId`, `projectId`, `activeOnly`

---

### `POST /api/admin/org/assignments`
Creates a new project role assignment.

**Roles:** Admin  
**Body:**
```json
{
  "associateId": "assoc-01",
  "projectId": "proj-01",
  "role": "Senior Engineer",
  "validFrom": "2026-01-01",
  "validTo": null,
  "allocationPercent": 100
}
```

---

### `POST /api/admin/org/assignments/move`
Records a mid-period project or role change (closes current assignment, opens a new one atomically).

**Roles:** Admin  
**Body:**
```json
{
  "associateId": "assoc-01",
  "fromProjectId": "proj-01",
  "toProjectId": "proj-02",
  "newRole": "Lead",
  "effectiveDate": "2026-04-15"
}
```

---

## Identity Resolution

### `GET /api/identity/unmatched`
Reports tool accounts that could not be automatically mapped to any associate.

**Roles:** Manager, ServiceHead, Admin  
**Response:** Array of `{ "tool": "Git", "handle": "ghost-dev", "activitiesCount": 12 }`

---

### `POST /api/identity/link`
Manually links a tool account to an associate (creates a `manual_override` identity record).

**Roles:** Admin  
**Body:**
```json
{
  "associateId": "assoc-07",
  "tool": "Git",
  "accountHandle": "ghost-dev"
}
```

---

### `DELETE /api/identity/link/{id}`
Removes a manual identity link (reverts to automatic matching).

**Roles:** Admin

---

## Activities

### `GET /api/activities`
Queries canonical activities. Filtered by role scope.

**Query Params:** `associateId`, `projectId`, `type`, `from`, `to`, `page`, `size`

---

### `GET /api/activities/{id}`
Returns a single activity with all its overrides.

---

### `POST /api/activities/manual`
Creates a manual data entry (first-class, not an override of existing data).

**Roles:** Manager, Admin  
**Body:**
```json
{
  "associateId": "assoc-03",
  "projectId": "proj-02",
  "dimension": "Delivery",
  "metricName": "Ad-hoc Architecture Spike Hours",
  "value": 16,
  "unit": "hours",
  "reason": "Led unplanned DB migration during client incident"
}
```

---

### `POST /api/activities/{id}/overrides`
Applies a manual correction to an existing activity field. Keeps the original value in the audit trail.

**Roles:** Manager (own team), Admin  
**Body:**
```json
{
  "fieldName": "storyPoints",
  "newValue": 8,
  "reason": "Story was re-estimated after sprint review"
}
```

---

## Manager Context Notes

### `GET /api/notes`
Lists context notes for associates in scope.

**Query Params:** `associateId`, `projectId`, `periodLabel`

---

### `POST /api/notes`
Creates a context note that adjusts the scoring baseline.

**Roles:** Manager (own team), Admin  
**Body:**
```json
{
  "associateId": "assoc-05",
  "projectId": "proj-01",
  "periodLabel": "2026-Q3",
  "category": "Leave",
  "impactDays": 10,
  "description": "Approved annual leave — 10 working days",
  "baselineAdjustmentPercent": -20
}
```

**Category values:** `Leave`, `Onboarding`, `On-Call Firefighting`, `Mentorship`, `Special R&D Assignment`

---

## Performance Models

### `GET /api/models`
Lists all model versions.

**Roles:** ServiceHead, Admin

---

### `GET /api/models/{versionId}`
Returns a model version's full configuration.

---

### `POST /api/admin/models`
Creates a new draft model version.

**Roles:** Admin

---

### `PUT /api/admin/models/{versionId}/publish`
Publishes a model (makes it the active model). Requires `rationaleDocumentation` to be non-empty.

**Roles:** Admin

---

## Scoring

### `POST /api/scoring/run`
Triggers a scoring run for a given associate × project × period.

**Roles:** ServiceHead, Admin  
**Body:**
```json
{
  "associateId": "assoc-01",
  "projectId": "proj-01",
  "periodStart": "2026-07-01",
  "periodEnd": "2026-09-30",
  "periodLabel": "2026-Q3"
}
```

---

### `GET /api/scores`
Lists scoring results in scope.

**Query Params:** `associateId`, `projectId`, `periodLabel`

---

### `GET /api/scores/{id}/explain`
Returns the full calculation trace for a score result: raw value → pro-rated → percentile → weight → contribution, plus exclusion reasons.

**Roles:** Employee (own), Manager (team), ServiceHead, Admin

---

### `GET /api/scores/{id}/evidence`
Drill-down to the source activity records that were used in the calculation.

---

## Reports

### `GET /api/reports/projects/{id}`
Project-level performance report — all associates, aggregated dimensions, health score.

**Roles:** Manager (led projects), ServiceHead, Admin

---

### `GET /api/reports/associates/{id}`
Individual associate performance report for the most recent scoring period.

**Roles:** Employee (own), Manager (team), ServiceHead, Admin

---

### `GET /api/reports/associates/{id}/trend`
Monthly trend data for an associate. Returns an array of monthly `SCORE_RESULT` summaries.

---

## Insights

### `POST /api/insights/detect-anomalies`
Runs the 8 deterministic anti-gaming detection rules against a score result.

**Roles:** ServiceHead, Admin  
**Body:** `{ "scoreResultId": "score-01" }`

---

### `GET /api/insights/flags`
Lists anti-gaming flags in scope.

**Query Params:** `associateId`, `scoreResultId`, `severity`, `dismissed`

---

### `POST /api/insights/flags/{id}/dismiss`
Marks a flag as reviewed and dismissed.

**Roles:** ServiceHead, Admin  
**Body:** `{ "reason": "False positive — confirmed with team lead" }`

---

### `POST /api/insights/generate-ai-summary`
Triggers AI narrative generation for a score result.

**Roles:** ServiceHead, Admin  
**Body:** `{ "scoreResultId": "score-01" }`

---

## Tool Connectors (Admin)

### `GET /api/admin/tool-configs`
Lists all tool connector configurations.

**Roles:** Admin

---

### `POST /api/admin/tool-configs`
Creates a new connector configuration for a project.

**Roles:** Admin  
**Body:**
```json
{
  "projectId": "proj-01",
  "tool": "JIRA",
  "name": "Psiog Jira Cloud",
  "mode": "LIVE",
  "endpointUrl": "https://psiog.atlassian.net",
  "authType": "API Token",
  "credentialRef": "jira"
}
```

---

### `PUT /api/admin/tool-configs/{id}/mapping`
Updates the JSONPath field mappings for a connector (per-project override of the default mapping).

**Roles:** Admin  
**Body:** `{ "customfield_10028": "storyPoints", "status.name": "status" }`

---

### `POST /api/admin/tool-configs/{id}/import`
Imports a CSV or JSON export file into the connector's activity stream.

**Roles:** Admin  
**Body:** `multipart/form-data` with `file` field

---

### `POST /api/admin/tool-configs/{id}/sync`
Triggers an immediate incremental sync for the connector.

**Roles:** Admin

---

## Health

### `GET /actuator/health`
Returns the application health status. Public endpoint.

### `GET /actuator/info`
Returns application metadata (version, build info).

---

## Error Responses

All error responses follow this structure:

```json
{
  "timestamp": "2026-09-29T10:30:00Z",
  "status": 403,
  "error": "Forbidden",
  "message": "Access denied: associate assoc-07 is not in your management scope",
  "path": "/api/reports/associates/assoc-07"
}
```

| HTTP Status | When |
|-------------|------|
| `400` | Invalid request body or query parameters |
| `401` | Missing or invalid JWT |
| `403` | Valid JWT but insufficient role or access scope |
| `404` | Entity not found |
| `409` | Conflict (e.g. duplicate identity link, model already active) |
| `422` | Business rule violation (e.g. insufficient data for scoring) |
| `500` | Unexpected server error |
