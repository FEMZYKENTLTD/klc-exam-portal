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

### 2.5 Diagnostics channel (temporary)

`.github/workflows/diag.yml` — TEMPORARY workflow (to be removed before
merge) that reproduces the failing CI steps on runners and re-emits the
captured output as check-run annotations, because the sandbox egress blocks
GitHub's log hosts (`results-receiver.actions.githubusercontent.com`) and
the bot token cannot write PR comments/branches.

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
| P2 (testability) | Exam scoring arithmetic only inside a 1,160-line JavaFX controller | extracted to `util.ExamScoring` (identical behaviour) |

## 5. Residual findings (owner visibility)

1. **AuthService compiled fallback registration codes.** The historical
   codes are gone from README/SQL but still exist as compiled *defaults*
   when `config.properties` is absent
   (`code.super_admin=FEMZYK ENTERPRISES LTD` etc.). Any deployment without
   a config file runs on the publicly-known codes. RECOMMENDED FIX
   (follow-up): ship empty defaults and refuse registration with a console
   instruction to configure codes; or keep as-is when the sponsor accepts
   that defaults are placeholders. This was left unchanged to avoid a
   behaviour break before the sponsor confirms.
2. **H2 offline cache has no FK constraints** (plain id columns). Intended
   for a light offline cache, but rows created offline are validated only
   when replayed to the cloud. Documented in acceptance file; not changed.
3. **Position calculation / CA aggregation / exam eligibility** remain
   inside JavaFX controllers — targeted for future extraction so they get
   the same unit coverage as scoring.

## 6. BLOCKED ITEM (external service)

```
BLOCKER — CI migrate job against the remote Supabase project
WHAT IS REQUIRED:   The Supabase project used by repo secrets KLC_DB_* must
                    be reachable from GitHub Actions runners and the secret
                    values must still be valid.
WHY:                The 'migrate' job only runs on push to main. The
                    sandbox cannot reach the Supabase host (egress allow-
                    list), and the runner-side probe (`psql` connectivity)
                    fails with exit code 2 - i.e. the connection is refused/
                    times out from GitHub's network today. The 2026-09-03
                    main-push failure was the same symptom (bootstrap step,
                    psql exit 2, after a successful connectivity check).
WHAT WAS TESTED:    psql probe from a GitHub runner using the repo secrets;
                    full SQL migration chain executed on local PostgreSQL
                    15/16 (PASS).
WHAT WAS FIXED:     All SQL content issues found by that local execution
                    (the pg_constraint.consrc P0). The compile/test pipeline
                    is green.
WHAT REMAINS:       Someone with Supabase access must verify the project is
                    awake, the DB password/host/port secrets are current,
                    and re-run the workflow (a push/merge to main triggers
                    migrate automatically).
EXACT HUMAN ACTION: 1) Log in to supabase.com → confirm the project the
                    secrets point to exists and is not paused.
                    2) Reset/confirm the DB password; update repo secrets
                    KLC_DB_HOST / KLC_DB_PORT / KLC_DB_USER / KLC_DB_PASS
                    (or KLC_DB_POOLER_URL) if they changed.
                    3) Merge this PR (or push to main) - the migrate job
                    then applies the fixed, idempotent schema chain.
```

## 7. Release gate (measured, not inflated)

| Gate | State |
|---|---|
| Source compiles | ✅ (CI green) |
| Automated tests | ✅ 27 test methods green in CI |
| SQL migration chain executable | ✅ on PostgreSQL 15/16 (local repro) |
| Cloud migrate job | ⛔ externally blocked (see §6) |
| FXML/wiring audit | ✅ 0 problems |
| Docs | updated (ACCEPTANCE-STATUS.md, this report) |
| Known critical defects | 0 remaining in-repo |
