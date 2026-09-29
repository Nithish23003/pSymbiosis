# Contributing to Perf Insight — Backend

This guide ensures that all contributors (Nithin, Nithish, and future team members) can collaborate on the backend repo without code loss or conflicts.

---

## Repo Ownership

| Repo | Owner GitHub Account | Primary Dev(s) |
|------|---------------------|----------------|
| `Nithish23003/pSymbiosis` (backend) | Nithish23003 | Nithish |
| `NithinReddy22/pSymbiosis` (frontend) | NithinReddy22 | Nithin |

The backend repo is **public**. Do not commit credentials, API tokens, or `.env` files. Use environment variables only (see `.env.example`).

---

## Branch Strategy

```
main            ← always stable, deployable
  └── feat/<short-description>     ← feature work
  └── fix/<short-description>      ← bug fixes
  └── docs/<short-description>     ← documentation only
  └── chore/<short-description>    ← dependency bumps, config changes
```

**Never commit directly to `main`.** Always work on a branch and open a Pull Request.

---

## Day-to-Day Workflow

### Before starting any work

```bash
git checkout main
git pull origin main          # sync with latest changes
git checkout -b feat/my-feature
```

### While working

Commit often in small, logical chunks:

```bash
git add src/path/to/changed/file.java
git commit -m "feat: add fuzzy match fallback to identity resolution"
```

### Before opening a PR

```bash
git fetch origin
git rebase origin/main        # replay your commits on top of latest main
# fix any conflicts, then:
git push origin feat/my-feature
```

**Prefer `rebase` over `merge`** when syncing with main — it keeps the commit history linear and readable.

### If you've been away for a few days

```bash
git checkout main && git pull origin main
git checkout your-branch
git rebase origin/main
```

---

## Commit Message Format

Use **Conventional Commits** format:

```
<type>: <short description in imperative mood>

[optional body]
```

| Type | When to use |
|------|-------------|
| `feat` | New feature or endpoint |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `refactor` | Code restructure (no behaviour change) |
| `test` | Adding or updating tests |
| `chore` | Dependency updates, config, build scripts |

**Examples:**
```
feat: add SharePoint connector incremental delta sync
fix: correct reopen-rate calculation when no tickets exist
docs: add ER diagram and API reference to docs/
refactor: extract MeasureCatalog from ScoringService
test: add ScoringMathTest for sparse-data edge case
```

Keep the first line under 72 characters. No trailing period.

---

## Pull Request Rules

1. **Every PR must target `main`** — no cross-branch PRs unless coordinated.
2. **Title** follows the same Conventional Commits format as commit messages.
3. **Description** must include:
   - What changed and why
   - How to test it (specific endpoints or test commands)
   - Any migration steps if DB schema changed
4. **Minimum 1 review** before merging — do not self-merge.
5. **All CI checks must pass** before merge.
6. Delete your branch after merge.

---

## Avoiding Conflicts

### Schema changes (Hibernate / DB)
- One person changes schema at a time — coordinate over chat first.
- If two people are changing different entities, that's fine in parallel.
- Run `mvn test` before pushing — tests will catch mapping errors.

### `application.yml` changes
- Only change `application.yml` for structural config (new fields, new profiles).
- Never commit actual secrets — always use `${ENV_VAR:}` pattern.
- If you add a new config key, also add it to `.env.example` with a placeholder value.

### Field mapping JSONs (`resources/mappings/*.json`)
- Treat these as shared config. Discuss before changing — a mapping change affects all projects using that tool type.
- Per-project overrides go through the API (`PUT /api/admin/tool-configs/{id}/mapping`), not file edits.

---

## Security Rules (Public Repo)

- **Never commit:** API tokens, passwords, `client_secret`, any `.env` file.
- **Always use:** `${ENV_VAR:}` placeholders in YAML. Reference secrets by name in `app.credentials.*`.
- If you accidentally commit a secret: rotate the credential immediately, then purge the commit from history (contact repo owner).
- Review `git diff` before `git push` — look for anything that looks like a key or password.

---

## Running Tests

```bash
mvn test
```

Tests are in `src/test/java/com/psiog/perfinsight/` and cover:
- `ScoringMathTest` — scoring algorithm correctness
- `MappingEngineTest` — JSONPath field mapping
- `JiraUserMatchingTest` — fuzzy identity matching

Add a test for every bug fix and every new service method.

---

## Coordinating Between Frontend and Backend

| Concern | Approach |
|---------|----------|
| New API endpoint | Backend adds endpoint → update `requests.http` → notify frontend dev → frontend integrates |
| Breaking API change | Discuss before merging to main — frontend must be updated in the same sprint |
| Shared types | TypeScript types in the frontend (`src/types/index.ts`) must stay in sync with Java DTOs |
| Auth changes | Any change to role mapping or JWT handling must be communicated to frontend dev immediately |

---

## If You Get Stuck

```bash
# Discard local uncommitted changes (use carefully)
git restore src/path/to/file.java

# See what changed on main since you branched
git log origin/main..HEAD --oneline

# Stash work in progress before switching branches
git stash
git stash pop   # restore later

# Undo last commit but keep changes staged
git reset --soft HEAD~1
```

Contact the repo owner (Nithish) before doing any force-push or history rewrite on `main`.
