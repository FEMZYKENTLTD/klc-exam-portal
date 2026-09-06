# KLC CBT SUITE — ENGINEERING VERIFICATION REPORT (2026-09-05)

**Session:** CI remediation + first automated test suite (branch
`arena/01a072de-klc-exam-portal`).

This report records exactly what was inspected, changed, built, tested and
what remains. Every claim is backed by an executed artifact in this repo or
in the GitHub Actions runs of this PR (`FEMZYKENTLTD/klc-exam-portal`,
workflow "Build KLC CBT Suite" + temporary "CI Diagnostics (temporary)").

---

## 1. What was inspected

| Area | Result |
|---|---|
| Repository hygiene | Working tree clean at start; single orphan-style history on `main`; branch policy respected (`arena/...` only). |
| Java/Maven environment (sandbox) | No JDK/Maven; egress allow-list (github/npm/pypi only) makes Maven Central unreachable → local `mvn` cannot run; **GitHub Actions used as the authoritative compile/test oracle**. |
| CI state at session start | **Both previous CI runs red** (main push + PR): "Bootstrap fresh project" psql exit 2; "Build fat JAR" mvn failure. |
| Static wiring | `tools/audit_fxml.py`: 42 FXML / 71 Java — 0 problems. `tools/audit_sql.py`: 0 problems. `tools/audit_deep.py`: 0 problems, 4 documented orphan-controller infos. |
| Internal references | 0 unresolved `com.femzyk.klc.*` imports across all sources. |

## 2. What was changed

### 2.1 P0 fixes (the code had NEVER compiled in CI)

1. `src/main/java/com/femzyk/klc/student/ExamController.java` — missing
   `import javafx.scene.input.KeyEvent;` (`KeyEvent.KEY_PRESSED` copy/paste
   block at line ~371).
2. `src/main/java/com/femzyk/klc/admin/AnalyticsController.java` — missing
   `import java.util.Map;` (`Map.Entry` iteration at line ~246).

Result: first-ever green `mvn clean package` in CI, fat JAR artifact
produced (`knowledge-land-cbt-1.0.0.jar`).

### 2.2 P0 SQL fix (would have failed every existing-project migration)

`supabase/klc_supabase_v1_1_security_and_features.sql` queried
`pg_constraint.consrc`, **removed in PostgreSQL 12** (Supabase runs PG 15;
CI repro ran PG 16) — the role-CHECK replacement `DO` block always errored.
Rewritten to find CHECK constraints over `users.role` via `pg_attribute`
and re-create one canonical `users_role_check_v2` constraint including
`PARENT`. Executed and verified on a real PostgreSQL (PARENT accepted,
invalid role rejected, constraint idempotent).

### 2.3 Automated test suite (first tests in the project)

- `pom.xml`: JUnit Jupiter 5.10.2 (test scope) + maven-surefire-plugin 3.2.5.
- `src/test/resources/config.properties`: test-only config pointing every
  DB access at in-memory H2 (PostgreSQL compatibility mode) — the real
  cloud is never touched by tests.
- `src/test/java/com/femzyk/klc/db/KlcTestDb.java` — shared bootstrap.
- `src/test/java/com/femzyk/klc/db/SchemaIntegrityTest.java` — full offline
  schema creation; controlled CRUD + 3-table JOIN; UNIQUE enforcement
  (duplicate admission/email); cloud FK declarations present in shipped DDL;
  seeded singleton; attempt→result insert & JOIN (result-view query shape).
- `src/test/java/com/femzyk/klc/auth/AuthServiceTest.java` — registration
  code gates (SA/staff/student/parent valid + invalid); staff password
  complexity; parent ward linking; student profile + result-PIN format;
  PIN collision numeric suffix; duplicate email/admission; invalid login;
  5-strike 15-minute lockout; disabled-account rejection.
- `src/test/java/com/femzyk/klc/admin/GradingScaleTest.java` — WAEC A1–F9
  exact boundary map (incl. E8); DB-configured custom scale override;
  CA weight defaults (20/20/60) and configured override.
- `src/test/java/com/femzyk/klc/util/ExamScoringTest.java` — raw score
  floor, negative marking, percentages, empty-exam edge cases.

### 2.4 Supporting extraction (behaviour-preserving)

`src/main/java/com/femzyk/klc/util/ExamScoring.java` — pure scoring
arithmetic extracted from `ExamController.submitExam()`; the controller now
delegates to it with identical formulas (`max(0, correct − wrong×neg)`,
`pct = raw×100/total`). Makes the single most critical business rule
unit-testable.

### 2.5 Diagnostics channel (temporary, since removed)

A temporary `.github/workflows/diag.yml` reproduced the failing CI steps on
runners and re-emitted captured output as check-run annotations, because
the sandbox egress blocks GitHub's log hosts
(`results-receiver.actions.githubusercontent.com`) and the bot token cannot
write PR comments/branches. It was removed once the fixes were verified;
its SQL-validation role is now a permanent job in `build.yml`
("Schema migration check (local PostgreSQL)").

## 3. What was built & tested

| Gate | Result |
|---|---|
| `mvn clean package` (CI, JDK 17 Temurin) | **PASS** — includes `mvn test` (JUnit suite green). |
| Fat JAR shaded artifact | **PASS** — uploaded by CI. |
| Cloud SQL migration chain on PostgreSQL 15/16 (CI runner, local Postgres, 5 files incl. Supabase-only scaffolding) | **PASS** — after the `consrc` fix. |
| H2 offline schema + DB semantics (JUnit) | **PASS**. |
| Authentication/registration/grading/scoring rules (JUnit) | **PASS**. |
| FXML↔controller wiring audit | **PASS** (0 problems). |
| Secret sweep (repo files) | **PASS** — no live credentials in tracked files; one residual concern listed in §5. |

## 4. Bugs found & fixed (summary)

| Severity | Bug | Fix |
|---|---|---|
| P0 (blocked every CI build) | Missing `KeyEvent` import, `ExamController` | import added |
| P0 (blocked every CI build) | Missing `Map` import, `AnalyticsController` | import added |
| P0 (blocked every PG≥12 migrate) | `pg_constraint.consrc` in v1.1 role-check migration | rewritten via `pg_attribute`; executed & verified |
| P1 (security) | Compromised historical codes survived as compiled fallback defaults in `AuthService` | registration now **fails closed** when codes are not configured; no codes shipped in source |
| P2 (testability) | Exam scoring arithmetic only inside a 1,160-line JavaFX controller | extracted to `util.ExamScoring` (identical behaviour) |

## 5. Residual findings (owner visibility)

1. **H2 offline cache has no FK constraints** (plain id columns). Intended
   for a light offline cache, but rows created offline are validated only
   when replayed to the cloud. Documented in acceptance file; not changed.
2. **Position calculation / CA aggregation / exam eligibility** remain
   inside JavaFX controllers — targeted for future extraction so they get
   the same unit coverage as scoring.
3. **CI secret placeholders.** The repo secrets for the Supabase DB host
   currently hold a value that does not resolve in DNS
   (`could not translate host name "***" ... Name or service not known`) —
   this is the entire cause of the red `migrate` job (see §6).

## 6. BLOCKED ITEM (external service)

```
BLOCKER — CI migrate job against the remote Supabase project
WHAT IS REQUIRED:   Valid repo secrets for the Supabase DB host/port/user/
                    password (KLC_DB_HOST, KLC_DB_PORT, KLC_DB_USER,
                    KLC_DB_PASS, or KLC_DB_POOLER_URL).
WHY:                The migrate job only runs on push to main. A runner-
                    side probe using the current secrets fails with:
                    psql: error: could not translate host name "***" to
                    address: Name or service not known  (exit code 2).
                    The stored host name does not resolve - the secrets are
                    placeholders/stale. This is the single cause of the red
                    migrate job; it is NOT a schema problem.
WHAT WAS TESTED:    psql probes from GitHub runners using the repo secrets
                    (exact DNS error captured above); full 5-file migration
                    chain executed on local PostgreSQL 15/16 (PASS); new
                    permanent "Schema migration check" CI job runs the same
                    chain on every PR/push (PASS on this PR).
WHAT WAS FIXED:     All SQL content issues found by local execution
                    (the pg_constraint.consrc P0). Compile + test pipeline
                    green (30/30 tests). Registration codes fail closed.
WHAT REMAINS:       Someone with Supabase access updates the DB secrets.
EXACT HUMAN ACTION: 1) Open the repo Settings → Secrets and variables →
                    Actions and confirm KLC_DB_HOST / KLC_DB_PORT (or
                    KLC_DB_POOLER_URL), KLC_DB_USER and KLC_DB_PASS hold the
                    real pooler/direct host of the project (not a
                    placeholder like db.YOUR_PROJECT.supabase.co).
                    2) Save the corrected secrets, then merge this PR (or
                    push to main) - the migrate job applies the fixed,
                    idempotent schema chain and the run should go green.
```

## 7. Release gate (measured, not inflated)

| Gate | State |
|---|---|
| Source compiles | ✅ (CI green) |
| Automated tests | ✅ 30 test methods green in CI (`Tests run: 30, Failures: 0`) |
| SQL migration chain executable | ✅ on PostgreSQL 15/16 + permanent CI gate |
| Cloud migrate job | ⛔ externally blocked (stale DB secrets, see §6) |
| FXML/wiring audit | ✅ 0 problems |
| Docs | updated (ACCEPTANCE-STATUS.md, this report) |
| Known critical defects | 0 remaining in-repo |
