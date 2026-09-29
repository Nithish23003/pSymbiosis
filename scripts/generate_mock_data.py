#!/usr/bin/env python3
"""
Generates realistic, API-shaped mock data for the demo (acceptance criterion 11):
  - several projects across three offerings
  - people whose identities differ across tools (personal emails, odd logins, hidden Jira emails)
  - people who move project / role mid-period (Vikram: PAY -> CLM, Rahul: Senior on PAY -> Lead on CLM)
  - leave (Lakshmi), a mid-period joiner (Meera), unmatched accounts (contractor, bot)
  - one engineer with gaming-like patterns (Rohan) so anomaly detection has something to find

Output files are shaped exactly like the real API responses, so MOCK -> LIVE is a config switch.
Run:  python3 scripts/generate_mock_data.py      (writes into src/main/resources/mock/)
"""
import csv
import json
import random
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

random.seed(42)
OUT = Path(__file__).resolve().parent.parent / "src" / "main" / "resources" / "mock"
START, END = date(2026, 6, 1), date(2026, 9, 25)
IST = timezone(timedelta(hours=5, minutes=30))

# ---------------------------------------------------------------------------------------------------
# People: corporate identity + how they appear in each tool
# ---------------------------------------------------------------------------------------------------
P = {
    "priya":   dict(name="Priya Sharma", email="priya.sharma@psiog.com", gh="priya-sharma", gh_email="priya.sharma@psiog.com"),
    "arjun":   dict(name="Arjun Mehta", email="arjun.mehta@psiog.com", gh="arjunm", gh_email="arjun.mehta@psiog.com"),
    "kavya":   dict(name="Kavya Reddy", email="kavya.reddy@psiog.com", gh="kavya-r", gh_email="kavya.reddy@psiog.com"),
    "rahul":   dict(name="Rahul Verma", email="rahul.verma@psiog.com", gh="rahulv", gh_email="rahul.verma@psiog.com"),
    "sneha":   dict(name="Sneha Iyer", email="sneha.iyer@psiog.com", gh="sneha-iyer", gh_email="sneha.iyer@psiog.com"),
    # Jira hides Vikram's email (privacy setting) -> resolved by display name (fuzzy)
    "vikram":  dict(name="Vikram Nair", email="vikram.nair@psiog.com", gh="vikramn", gh_email="vikram.nair@psiog.com", jira_hide_email=True),
    # ADO shows an onmicrosoft alias -> fuzzy name match to confirm
    "ananya":  dict(name="Ananya Gupta", email="ananya.gupta@psiog.com", gh="ananya-g", gh_email="ananya.gupta@psiog.com", ado_unique="ananya.g@psiog.onmicrosoft.com"),
    # GitHub login + PERSONAL email, name abbreviated -> stays UNMATCHED until an admin links it
    "karthik": dict(name="Karthik Subramanian", email="karthik.subramanian@psiog.com", gh="ksubbu", gh_email="karthik.s@gmail.com", gh_name="Karthik S"),
    # TestRail uses a short email alias -> fuzzy name match
    "divya":   dict(name="Divya Menon", email="divya.menon@psiog.com", gh="divya-menon", gh_email="divya.menon@psiog.com", tr_email="divya.m@psiog.com"),
    "suresh":  dict(name="Suresh Babu", email="suresh.babu@psiog.com", gh="sureshb", gh_email="suresh.babu@psiog.com"),
    "meera":   dict(name="Meera Pillai", email="meera.pillai@psiog.com", gh="meera-p", gh_email="meera.pillai@psiog.com"),
    "aditya":  dict(name="Aditya Rao", email="aditya.rao@psiog.com", gh="adityarao", gh_email="aditya.rao@psiog.com"),
    "lakshmi": dict(name="Lakshmi Narayanan", email="lakshmi.narayanan@psiog.com", gh="lakshmi-n", gh_email="lakshmi.narayanan@psiog.com"),
    # login + personal email; the seeder registers "rk-dev" as a known alias -> AUTO_ALIAS
    "rohan":   dict(name="Rohan Das", email="rohan.das@psiog.com", gh="rk-dev", gh_email="rohan.dev.personal@gmail.com", gh_name="RK"),
}
for i, (k, p) in enumerate(P.items()):
    p["jira_id"] = f"712020:{k}-{1000 + i}"
    p["ado_id"] = f"{i:08x}-ado0-4000-8000-{k[:4]:0>12}"
    p["tr_id"] = 100 + i

# timeline: (project, persona, start, end)
T = {
    "priya":   [("PAY", "LEAD", START, END)],
    "arjun":   [("QAX", "LEAD", START, END)],
    "kavya":   [("INS", "LEAD", START, END)],
    "rahul":   [("PAY", "SENIOR_ENGINEER", START, date(2026, 7, 31)), ("CLM", "LEAD", date(2026, 8, 1), END)],
    "sneha":   [("PAY", "ENGINEER", START, END)],
    "vikram":  [("PAY", "ENGINEER", START, date(2026, 7, 31)), ("CLM", "ENGINEER", date(2026, 8, 1), END)],
    "ananya":  [("CLM", "SENIOR_ENGINEER", START, END)],
    "karthik": [("CLM", "ENGINEER", START, END)],
    "divya":   [("QAX", "ENGINEER", START, END)],
    "suresh":  [("QAX", "SENIOR_ENGINEER", START, END)],
    "meera":   [("INS", "ENGINEER", date(2026, 7, 15), END)],
    "aditya":  [("INS", "SENIOR_ENGINEER", START, END)],
    "lakshmi": [("CLM", "ENGINEER", START, END)],
    "rohan":   [("PAY", "ENGINEER", START, END)],
}
LEAVE = {"lakshmi": (date(2026, 8, 10), date(2026, 8, 21))}
QA = {"divya", "suresh"}

PROJECT_TOOLS = {
    "PAY": dict(tracker="jira", repo="payments-api", tests="PAY", docs="PAY"),
    "CLM": dict(tracker="ado", repo="claims-portal", tests="CLM", docs="CLM"),
    "QAX": dict(tracker="jira", repo="qa-automation", tests="QAX", docs="QAX"),
    "INS": dict(tracker="jira_csv", repo="insights-lakehouse", tests=None, docs="INS"),
}

# per-week rates by persona (qa people get test-heavy rates)
RATES = {
    "ENGINEER":        dict(tickets=2.0, prs=1.6, reviews=1.5, tests=1.0, docs=0.3),
    "SENIOR_ENGINEER": dict(tickets=2.3, prs=1.8, reviews=2.5, tests=0.8, docs=0.5),
    "LEAD":            dict(tickets=0.8, prs=0.6, reviews=4.0, tests=0.3, docs=1.1),
    "QA":              dict(tickets=1.4, prs=0.7, reviews=0.8, tests=14.0, docs=0.5),
}
# individual performance multipliers so the cohort has a real spread
SKILL = {"priya": 1.1, "arjun": 1.0, "kavya": 0.95, "rahul": 1.15, "sneha": 1.25, "vikram": 1.0, "ananya": 1.2,
         "karthik": 0.8, "divya": 1.1, "suresh": 0.9, "meera": 0.75, "aditya": 1.05, "lakshmi": 0.95, "rohan": 0.9}

FEATURES = ["refund flow", "retry policy", "ledger export", "webhook signing", "FX rates cache", "claim intake form",
            "document upload", "adjuster dashboard", "fraud score API", "audit trail", "SLA timer", "search filters",
            "regression pack", "flaky test triage", "contract tests", "load profile", "dbt model", "ingestion job",
            "data quality checks", "lineage graph", "access policies", "cost dashboard", "schema migration", "rate limiter"]


def role_on(person, d):
    for proj, persona, s, e in T[person]:
        if s <= d <= e:
            return proj, persona
    return None, None


def on_leave(person, d):
    lv = LEAVE.get(person)
    return lv is not None and lv[0] <= d <= lv[1]


def workdays():
    d = START
    while d <= END:
        if d.weekday() < 5:
            yield d
        d += timedelta(days=1)


def at(d, hour_lo=9, hour_hi=19):
    return datetime(d.year, d.month, d.day, random.randint(hour_lo, hour_hi - 1), random.randint(0, 59), tzinfo=IST)


def iso_z(dt):
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def jira_ts(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000%z")


def happens(rate_per_week, mult=1.0):
    """Poisson-ish daily draw from a weekly rate."""
    lam = rate_per_week * mult / 5.0
    n = 0
    while random.random() < lam and n < 6:
        n += 1
        lam *= 0.5
    return n


def people_on(proj, d):
    return [k for k in T if role_on(k, d)[0] == proj and not on_leave(k, d)]


# ---------------------------------------------------------------------------------------------------
tickets = {p: [] for p in PROJECT_TOOLS}
prs = {p: [] for p in PROJECT_TOOLS}
commits = {p: [] for p in PROJECT_TOOLS}
results = {p: [] for p in PROJECT_TOOLS}
docs = {p: [] for p in PROJECT_TOOLS}
counters = {"PAY": 100, "QAX": 300, "INS": 500, "CLM": 4100}
pr_no = {p: 1 for p in PROJECT_TOOLS}
ids = {"review": 900000, "result": 70000, "test": 5000, "doc": 1}


def new_ticket(person, proj, d, persona):
    counters[proj] += 1
    tracker = PROJECT_TOOLS[proj]["tracker"]
    key = f"{proj}-{counters[proj]}" if tracker != "ado" else f"ADO-{counters[proj]}"
    created = at(d - timedelta(days=random.randint(1, 6)))
    started = created + timedelta(hours=random.randint(2, 30))
    base_cycle = random.uniform(1.0, 7.0) / SKILL[person]
    points = random.choice([1, 2, 2, 3, 3, 5, 5, 8])
    if person == "rohan":
        points = random.choice([8, 8, 13, 13, 5])          # estimate inflation
    resolved = at(d)
    if resolved < started + timedelta(hours=4):
        resolved = started + timedelta(hours=4 + base_cycle * 24)
    done = random.random() < 0.9
    reopened = random.random() < (0.22 if SKILL[person] < 0.9 else 0.08)
    return dict(key=key, person=person, proj=proj, created=created, started=started, resolved=resolved if done else None,
                updated=resolved, points=points, done=done, reopened=reopened, persona=persona,
                title=f"{random.choice(['Implement', 'Fix', 'Refactor', 'Add', 'Harden'])} {random.choice(FEATURES)}")


def new_pr(person, proj, d, ticket_key=None, tiny=False):
    n = pr_no[proj]
    pr_no[proj] += 1
    created = at(d)
    lines = random.randint(2, 12) if tiny else int(random.lognormvariate(4.8, 0.8))
    add = int(lines * random.uniform(0.55, 0.85))
    merged = created + timedelta(hours=random.uniform(3, 60) / SKILL.get(person, 1.0))
    title = (f"{ticket_key}: " if ticket_key else "") + random.choice(["", "chore: ", "fix: "]) + random.choice(FEATURES)
    return dict(n=n, person=person, proj=proj, created=created, merged=merged, lines=lines, add=add, title=title,
                branch=f"feature/{ticket_key or 'misc'}-{random.choice(FEATURES).replace(' ', '-')}", reviews=[],
                merged_by=None)


# ---------------------------------------------------------------------------------------------------
for d in workdays():
    for person in T:
        proj, persona = role_on(person, d)
        if proj is None or on_leave(person, d):
            continue
        mult = SKILL[person]
        if person == "meera" and d < date(2026, 8, 5):
            mult *= 0.45                                            # onboarding ramp-up
        r = RATES["QA" if person in QA else persona]
        # tickets
        for _ in range(happens(r["tickets"], mult)):
            t = new_ticket(person, proj, d, persona)
            tickets[proj].append(t)
            if random.random() < 0.85 and person not in QA:
                prs[proj].append(new_pr(person, proj, d, t["key"]))
        # extra PRs
        for _ in range(happens(r["prs"] * 0.35, mult)):
            prs[proj].append(new_pr(person, proj, d))
        if person == "rohan":                                        # many tiny PRs
            for _ in range(happens(4.0)):
                prs[proj].append(new_pr(person, proj, d, tiny=True))
        # tests
        if PROJECT_TOOLS[proj]["tests"]:
            for _ in range(happens(r["tests"], mult)):
                ids["result"] += 1
                ids["test"] += 1
                failed = random.random() < 0.18
                results[proj].append(dict(id=ids["result"], test_id=ids["test"], person=person, at=at(d),
                                          status=5 if failed else 1,
                                          defects=(f"{proj}-{counters[proj] + random.randint(1, 30)}" if failed and random.random() < 0.7 else "")))
        # docs
        for _ in range(happens(r["docs"], mult)):
            ids["doc"] += 1
            created = at(d)
            editor = person
            peers = people_on(proj, d)
            if random.random() < 0.4 and peers:
                editor = random.choice(peers)
            docs[proj].append(dict(id=f"01{proj}{ids['doc']:06d}", person=person, editor=editor, created=created,
                                   modified=created + timedelta(days=random.randint(0, 9), hours=random.randint(1, 8)),
                                   name=f"{random.choice(['Design', 'Runbook', 'ADR', 'Test strategy', 'Release notes', 'Spike'])} - {random.choice(FEATURES)}.docx"))

# Rohan closes a burst of tickets at the end of August (end-of-period spike)
for i in range(7):
    t = new_ticket("rohan", "PAY", date(2026, 8, random.choice([28, 31])), "ENGINEER")
    tickets["PAY"].append(t)

# unmatched accounts: an external contractor and a bot
for d in list(workdays())[::6]:
    pr = new_pr("__contractor", "CLM", d)
    pr["login"] = "ext-contractor-99"
    prs["CLM"].append(pr)
for d in list(workdays())[::9]:
    pr = new_pr("__bot", "PAY", d, tiny=True)
    pr["login"] = "dependabot[bot]"
    pr["title"] = "Bump library version"
    prs["PAY"].append(pr)

# reviews: peers on the same project at the time review PRs (Vikram still reviews PAY PRs in Aug after moving)
for proj, lst in prs.items():
    for pr in lst:
        d = pr["created"].date()
        author = pr["person"]
        peers = [p for p in people_on(proj, d) if p != author]
        if proj == "PAY" and d >= date(2026, 8, 1) and random.random() < 0.15:
            peers.append("vikram")
        random.shuffle(peers)
        weights = [RATES["QA" if p in QA else role_on(p, d)[1] or "ENGINEER"]["reviews"] for p in peers]
        chosen = []
        for p, w in zip(peers, weights):
            if random.random() < min(0.9, w / 6.0):
                chosen.append(p)
            if len(chosen) >= 2:
                break
        for p in chosen:
            ids["review"] += 1
            quick = p == "rohan" and random.random() < 0.8
            submitted = pr["created"] + (timedelta(minutes=random.randint(2, 7)) if quick else timedelta(hours=random.uniform(1, 20)))
            if quick and pr["lines"] < 300:
                pr["lines"] = random.randint(320, 800)
                pr["add"] = int(pr["lines"] * 0.7)
            pr["reviews"].append(dict(id=ids["review"], person=p, at=submitted,
                                      body="" if quick else random.choice(["LGTM, minor nit on naming", "Please add a test for the null path",
                                                                           "Looks good - consider extracting the mapper", "Approved after the retry change"]),
                                      state="APPROVED" if random.random() < 0.8 or quick else "CHANGES_REQUESTED"))
        # who merges
        if author == "rohan" and random.random() < 0.7:
            pr["merged_by"] = "rohan"
        elif pr["reviews"]:
            pr["merged_by"] = pr["reviews"][0]["person"]
        else:
            pr["merged_by"] = author


def gh_login(k):
    return P[k]["gh"] if k in P else None


# ---------------------------------------------------------------------------------------------------
# writers
# ---------------------------------------------------------------------------------------------------
def write_json(rel, obj):
    path = OUT / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, default=str))
    print(f"wrote {rel}")


def jira_issue(t):
    p = P[t["person"]]
    assignee = {"accountId": p["jira_id"], "displayName": p["name"], "active": True}
    if not p.get("jira_hide_email"):
        assignee["emailAddress"] = p["email"]
    histories = [{"created": jira_ts(t["started"]), "items": [{"field": "status", "fromString": "To Do", "toString": "In Progress"}]}]
    if t["reopened"] and t["done"]:
        mid = t["started"] + (t["resolved"] - t["started"]) / 2
        histories += [{"created": jira_ts(mid), "items": [{"field": "status", "fromString": "In Progress", "toString": "Done"}]},
                      {"created": jira_ts(mid + timedelta(hours=3)), "items": [{"field": "status", "fromString": "Done", "toString": "Reopened"}]}]
    if t["done"]:
        histories.append({"created": jira_ts(t["resolved"]), "items": [{"field": "status", "fromString": "In Progress", "toString": "Done"}]})
    status = {"name": "Done", "statusCategory": {"key": "done"}} if t["done"] else {"name": "In Progress", "statusCategory": {"key": "indeterminate"}}
    return {
        "key": t["key"],
        "self": f"https://psiog.atlassian.net/rest/api/3/issue/{t['key']}",
        "fields": {
            "summary": t["title"], "status": status,
            "issuetype": {"name": random.choice(["Story", "Story", "Bug", "Task"])},
            "priority": {"name": random.choice(["Medium", "High", "Low"])},
            "assignee": assignee,
            "created": jira_ts(t["created"]), "updated": jira_ts(t["updated"]),
            "resolutiondate": jira_ts(t["resolved"]) if t["resolved"] else None,
            "customfield_10016": t["points"],
        },
        "changelog": {"histories": histories},
    }


for proj in ["PAY", "QAX"]:
    write_json(f"jira/{proj}.json", {"issues": [jira_issue(t) for t in tickets[proj]]})

# INS: Jira server that can't be reached -> CSV export (Jira date format), no emails in export
with open(OUT / "jira" / "INS_export.csv", "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["Issue key", "Summary", "Issue Type", "Status", "Status Category", "Assignee", "Assignee Id",
                "Created", "Updated", "Resolved", "Custom field (Story Points)"])
    fmt = "%d/%b/%y %I:%M %p"
    for t in tickets["INS"]:
        p = P[t["person"]]
        w.writerow([t["key"], t["title"], "Story", "Done" if t["done"] else "In Progress", "Done" if t["done"] else "In Progress",
                    p["name"], p["jira_id"], t["created"].strftime(fmt), t["updated"].strftime(fmt),
                    t["resolved"].strftime(fmt) if t["resolved"] else "", t["points"]])
print("wrote jira/INS_export.csv")

# ADO work items for CLM
items = []
for t in tickets["CLM"]:
    p = P[t["person"]]
    wid = int(t["key"].split("-")[1])
    items.append({
        "id": wid, "rev": 5,
        "fields": {
            "System.TeamProject": "Claims", "System.WorkItemType": random.choice(["User Story", "Bug", "Task"]),
            "System.Title": t["title"], "System.State": "Closed" if t["done"] else "Active",
            "System.AssignedTo": {"displayName": p["name"], "uniqueName": p.get("ado_unique", p["email"]), "id": p["ado_id"]},
            "System.CreatedDate": iso_z(t["created"]), "System.ChangedDate": iso_z(t["updated"]),
            "Microsoft.VSTS.Common.ActivatedDate": iso_z(t["started"]),
            "Microsoft.VSTS.Common.ClosedDate": iso_z(t["resolved"]) if t["resolved"] else None,
            "Microsoft.VSTS.Scheduling.StoryPoints": t["points"],
            "Custom.ReopenCount": 1 if t["reopened"] and t["done"] else 0,
        },
        "_links": {"html": {"href": f"https://dev.azure.com/psiog/Claims/_workitems/edit/{wid}"}},
    })
write_json("ado/CLM.json", {"count": len(items), "value": items})

# GitHub
for proj, tools in PROJECT_TOOLS.items():
    repo = f"psiog/{tools['repo']}"
    out_prs, out_commits = [], []
    for pr in prs[proj]:
        login = pr.get("login") or gh_login(pr["person"])
        merged_by = pr.get("login") if pr["person"].startswith("__") else gh_login(pr["merged_by"])
        closed = pr["merged"]
        out_prs.append({
            "pr": {
                "number": pr["n"], "title": pr["title"], "state": "closed",
                "html_url": f"https://github.com/{repo}/pull/{pr['n']}",
                "user": {"login": login}, "created_at": iso_z(pr["created"]), "updated_at": iso_z(pr["merged"]),
                "closed_at": iso_z(closed), "merged_at": iso_z(pr["merged"]), "merged": True,
                "merged_by": {"login": merged_by}, "additions": pr["add"], "deletions": pr["lines"] - pr["add"],
                "changed_files": max(1, pr["lines"] // 40), "head": {"ref": pr["branch"]},
            },
            "reviews": [{
                "id": rv["id"], "user": {"login": gh_login(rv["person"])}, "state": rv["state"], "body": rv["body"],
                "submitted_at": iso_z(rv["at"]), "html_url": f"https://github.com/{repo}/pull/{pr['n']}#pullrequestreview-{rv['id']}",
            } for rv in pr["reviews"]],
        })
        if pr["person"].startswith("__"):
            email, name = (f"{login}@users.noreply.github.com", login)
        else:
            p = P[pr["person"]]
            email, name = p["gh_email"], p.get("gh_name", p["name"])
        for c in range(random.randint(1, 3)):
            sha = "%040x" % random.getrandbits(160)
            out_commits.append({
                "sha": sha, "html_url": f"https://github.com/{repo}/commit/{sha}",
                "commit": {"author": {"name": name, "email": email,
                                      "date": iso_z(pr["created"] - timedelta(hours=random.randint(1, 20)))},
                           "message": pr["title"]},
                "author": {"login": login},
            })
    write_json(f"github/{tools['repo']}.json", {"repo": repo, "pull_requests": out_prs, "commits": out_commits})

# TestRail
for proj in ["PAY", "CLM", "QAX"]:
    runs = {}
    out = []
    for r in results[proj]:
        week = r["at"].isocalendar()[1]
        run = runs.setdefault(week, {"id": 800 + week + (0 if proj == "PAY" else 100 if proj == "CLM" else 200),
                                     "name": f"{proj} sprint {week} regression",
                                     "url": f"https://psiog.testrail.io/index.php?/runs/view/{800 + week}"})
        p = P[r["person"]]
        out.append({"id": r["id"], "test_id": r["test_id"], "status_id": r["status"], "created_by": p["tr_id"],
                    "created_on": int(r["at"].timestamp()), "elapsed": f"{random.randint(1, 25)}m", "defects": r["defects"],
                    "run": run, "user": {"id": p["tr_id"], "name": p["name"], "email": p.get("tr_email", p["email"])}})
    write_json(f"testmgmt/{proj}.json", {"offset": 0, "limit": 250, "size": len(out), "results": out})

# SharePoint (Graph driveItems)
for proj in ["PAY", "CLM", "QAX", "INS"]:
    out = []
    for dct in docs[proj]:
        c, m = P[dct["person"]], P[dct["editor"]]
        out.append({
            "id": dct["id"], "name": dct["name"], "size": random.randint(20_000, 900_000),
            "webUrl": f"https://psiog.sharepoint.com/sites/{proj}/Shared%20Documents/{dct['name'].replace(' ', '%20')}",
            "createdDateTime": iso_z(dct["created"]), "lastModifiedDateTime": iso_z(dct["modified"]),
            "createdBy": {"user": {"email": c["email"], "displayName": c["name"], "id": c["ado_id"]}},
            "lastModifiedBy": {"user": {"email": m["email"], "displayName": m["name"], "id": m["ado_id"]}},
            "file": {"mimeType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document"},
            "parentReference": {"path": "/drive/root:/Shared Documents"},
        })
    write_json(f"sharepoint/{proj}.json", {"value": out, "@odata.deltaLink": f"https://graph.microsoft.com/v1.0/sites/{proj}/drive/root/delta?token=mock"})

print({k: len(v) for k, v in tickets.items()}, {k: len(v) for k, v in prs.items()})
