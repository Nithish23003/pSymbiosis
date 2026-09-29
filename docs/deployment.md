# Deployment Guide — Perf Insight Backend

## Prerequisites

| Tool | Version |
|------|---------|
| Java | 17+ |
| Maven | 3.9+ |
| Docker & Docker Compose | 24+ (for containerised database) |
| MySQL | 8.0+ (or PostgreSQL 14+) |

---

## Quick Start (Development)

### 1. Start the Database

```bash
docker compose up -d
```

This starts a MySQL instance pre-configured for local development (see `docker-compose.yml`).

### 2. Set Environment Variables

```bash
# Database (required)
export DB_URL=jdbc:mysql://localhost:3306/Perfromance_Platform
export DB_USERNAME=root
export DB_PASSWORD=Root@12345

# Azure AD (optional in dev — set open-access=true instead)
export AZURE_TENANT_ID=your-tenant-id
export AZURE_API_AUDIENCE=api://perf-insight

# Tool API tokens (only needed for LIVE connectors)
export JIRA_BASE_URL=https://your-domain.atlassian.net
export JIRA_API_TOKEN=your-jira-api-token
export GITHUB_TOKEN=ghp_...
export ADO_PAT=your-ado-pat
export TESTRAIL_USER=your@email.com
export TESTRAIL_API_KEY=your-testrail-key
export SHAREPOINT_CLIENT_ID=your-client-id
export SHAREPOINT_CLIENT_SECRET=your-client-secret

# AI Provider (optional — template fallback if not set)
export AI_PROVIDER=openai   # or: azure-openai
export AI_API_KEY=sk-...
export AI_MODEL=gpt-4o-mini
```

### 3. Run with Demo Data

```bash
# Maven wrapper (if committed):
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,demo

# System Maven:
mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo
```

> **Note:** If no Maven wrapper is committed, run `mvn -N wrapper:wrapper` once to generate it.

**What the `demo` profile seeds:**
- 3 offerings (Cloud & DevOps, Fullstack Web & Mobile, QA & Test Automation)
- 4 projects
- 14 associates
- Mock activities for all 5 tool types
- Identity mismatches, mid-period moves, leave events, a new joiner, and one engineer with anti-gaming patterns

**What the `dev` profile does:**
- Enables dev-mode header auth (`X-Dev-User: admin`) — bypasses Azure AD JWT validation
- Sets `app.security.open-access=true`

### 4. Verify

| Check | URL |
|-------|-----|
| Health | http://localhost:8080/actuator/health |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Test API | http://localhost:8080/api/jira/connection |

Use `requests.http` for a complete walkthrough of all acceptance criteria endpoints.

---

## Configuration Reference

All settings are in `src/main/resources/application.yml`. Environment variables override YAML values.

### Database

```yaml
spring:
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/Perfromance_Platform}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:Root@12345}
  jpa:
    hibernate:
      ddl-auto: update  # Use Flyway + validate in production
```

### Azure AD (Entra ID)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://login.microsoftonline.com/${AZURE_TENANT_ID:common}/v2.0
          audiences: ${AZURE_API_AUDIENCE:api://perf-insight}

app:
  security:
    open-access: false     # Set true to bypass JWT in development
    dev-auth: false        # Set true to enable X-Dev-User header
    role-mapping:
      Admin: ADMIN
      ServiceHead: SERVICE_HEAD
      Manager: MANAGER
      Employee: EMPLOYEE
    cors-allowed-origins:
      - http://localhost:3000
      - http://localhost:5173
```

### Sync Scheduler

```yaml
app:
  sync:
    enabled: true
    cron: "0 0 */6 * * *"        # Every 6 hours
    initial-lookback-days: 180
  scoring:
    snapshot-cron: "0 30 1 1 * *" # Monthly snapshot on the 1st
    working-days-per-normalised-period: 20
    min-active-days: 5
    min-cohort-size: 3
    max-single-measure-weight: 0.40
```

### AI Provider

```yaml
app:
  ai:
    provider: ${AI_PROVIDER:none}    # none | openai | azure-openai
    api-key: ${AI_API_KEY:}
    model: ${AI_MODEL:gpt-4o-mini}
    # Azure OpenAI:
    azure-endpoint: ${AZURE_OPENAI_ENDPOINT:}
    azure-deployment: ${AZURE_OPENAI_DEPLOYMENT:}
    azure-api-version: ${AZURE_OPENAI_API_VERSION:2024-10-21}
```

---

## Azure AD App Registration Setup

1. **Create App Registration** for the API in Azure Portal.
2. **Expose an API**: Set Application ID URI to `api://perf-insight` (or set `AZURE_API_AUDIENCE` to match your value).
3. **Manifest**: Set `"requestedAccessTokenVersion": 2` for v2.0 token format.
4. **App Roles**: Create roles with these exact `value` strings:

   | Role Value | Description |
   |------------|-------------|
   | `Admin` | Platform admin |
   | `ServiceHead` | Practice/delivery head |
   | `Manager` | Project lead |
   | `Employee` | Individual contributor |

5. **Enterprise Applications → Users and groups**: Assign users or groups to the roles.
6. **Frontend**: Request scope `api://perf-insight/.default` and send `Authorization: Bearer <token>`.

---

## Tool Connector Setup

### Jira Cloud

```bash
export JIRA_BASE_URL=https://your-domain.atlassian.net
export JIRA_API_TOKEN=ATATT3xF...    # https://id.atlassian.com/manage-profile/security/api-tokens
```

Required token permissions: Read access to Issues, Projects, Users, Worklogs, Development info (if GitHub is connected to Jira).

### GitHub

```bash
export GITHUB_TOKEN=ghp_...
```

Required scopes (fine-grained token): `Pull requests: Read`, `Contents: Read`, `Metadata: Read`.

### Azure DevOps

```bash
export ADO_PAT=your-pat
```

Required PAT scope: `Work Items (Read)`.

### SharePoint (Graph API)

```bash
export SHAREPOINT_CLIENT_ID=your-client-id
export SHAREPOINT_CLIENT_SECRET=your-client-secret
export AZURE_TENANT_ID=your-tenant-id
```

Required application permission: `Sites.Read.All`.

### TestRail

```bash
export TESTRAIL_USER=your@email.com
export TESTRAIL_API_KEY=your-api-key
```

---

## Production Checklist

- [ ] Replace `ddl-auto: update` with Flyway migrations (`ddl-auto: validate`).
- [ ] Set `app.security.open-access=false` and `app.security.dev-auth=false`.
- [ ] Configure proper `cors-allowed-origins` for the production frontend URL.
- [ ] Rotate all API tokens and store in a secrets manager (Azure Key Vault, AWS Secrets Manager, etc.).
- [ ] Set `AI_PROVIDER` to `openai` or `azure-openai` for live AI summaries.
- [ ] Configure a production-grade database with connection pooling (HikariCP default settings are reasonable).
- [ ] Set `logging.level.com.psiog.perfinsight` to `WARN` in production.
- [ ] Enable HTTPS / TLS termination at the reverse proxy / load balancer.
- [ ] Set up monitoring on `GET /actuator/health`.
- [ ] Review and tighten `sync.initial-lookback-days` for the first production sync.

---

## Docker Compose (Development)

The included `docker-compose.yml` starts a MySQL database for local development:

```bash
docker compose up -d     # Start
docker compose down      # Stop
docker compose down -v   # Stop and remove volumes (clears all data)
```

---

## Building a Production JAR

```bash
mvn clean package -DskipTests
java -jar target/perf-insight-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

---

## Running Tests

```bash
mvn test
```

Tests use an H2 in-memory database and cover:
- Scoring engine mathematics (correct weighted calculation, pro-rating, percentile logic)
- Field mapping engine (JSONPath resolution, custom field overrides)
- Identity resolution (email match, alias match, fuzzy match priority)
- Anti-gaming rule detection
