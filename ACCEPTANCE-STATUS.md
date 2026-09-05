# KLC CBT SUITE — ACCEPTANCE STATUS

> Maintained continuously during development (project rule 8).
> Every status below is measured against actual evidence produced in this
> repository/CI — a feature is never marked PASS on the strength of UI
> elements alone. Evidence IDs reference the sections of
> `docs/VERIFICATION-REPORT-2026-09-05.md`.

**Last updated:** 2026-09-05 (session: CI remediation + automated tests + 68-feature spec cross-check)

> **Spec cross-check:** full evidence-backed mapping of the sponsor's
> "68-feature v1.0 build plan" against the repository is in
> **`docs/FEATURE-MATRIX.md`** (every item → ✅/🟡/🟠/❌/⚠️ with file
> evidence). Aggregate: ~24 ✅ implemented+verified · ~23 🟡 implemented
> (desktop/cloud runtime not exercised in sandbox) · ~14 🟠 partial ·
> ~7 ❌/⚠️ not-found/overclaim. Headline gaps flagged there (F1–F7).

## Measured acceptance criteria

| Criterion | Status | Evidence |
|---|---|---|
| BUILD | **PASS** | `mvn clean package` green in GitHub Actions on PR head (`arena/01a072de`); fat JAR artifact `knowledge-land-cbt-1.0.0.jar` produced and uploaded. Fixed: missing `KeyEvent`/`Map` imports that had blocked every previous CI run. |
| TESTS (automated suite) | **PASS** | New JUnit 5 suite (JUnit Jupiter 5.10.2, surefire 3.2.5) runs in CI. See `src/test/java`; per-run `Tests run:` summary is captured in CI logs. |
| DATABASE (cloud schema) | **PASS** (with fix) | Full 5-file migration chain executes on PostgreSQL 15/16 in CI repro. Fixed P0: `klc_supabase_v1_1_security_and_features.sql` referenced `pg_constraint.consrc` (removed in PG 12) — rewrote role-CHECK replacement via `pg_attribute`; verified `PARENT` accepted / invalid role rejected. |
| DATABASE (offline H2) | **PASS** | `DatabaseInitializer` builds the full offline schema in-memory (H2, PostgreSQL mode) under test; CRUD, JOINs, UNIQUE constraints and seeded singleton verified in `SchemaIntegrityTest`. |
| FOREIGN KEYS | **PASS** (cloud) | Core FKs asserted present in `supabase/klc_supabase_schema.sql` (results→attempts, attempts→exams/users, answers→attempts/questions, options→questions, teacher_subjects→users/subjects, ca_scores→subjects). Note: the H2 offline cache intentionally stores plain ids without FK constraints (offline-only design). |
| AUTHENTICATION | **PASS** | BCrypt login, wrong-password rejection, 5-strike 15-minute lockout, disabled-account rejection, session state — all covered by `AuthServiceTest`. |
| REGISTRATION CODES / RBAC gates | **PASS** | Super-admin/staff/student registration-code gates verified (valid + invalid). Role routing helpers exist. |
| STUDENT REGISTRATION | **PASS** | Registration, duplicate-email rejection, duplicate-admission rejection, profile creation verified in `AuthServiceTest`. |
| RESULT PIN | **PASS** | `SURNAME+CLASS` format verified; collision handling (numeric suffix from admission no) verified deterministically. |
| PARENT PORTAL | **PASS** (data layer) | Parent role uses the family code and links a ward by admission number (`parent_profiles` insert verified). UI flow not exercised headless (JavaFX). |
| PASSWORD POLICY | **PASS** | Staff complexity rule (≥8 chars, upper+lower+digit) verified for accept/reject paths. |
| GRADING SCALE | **PASS** | WAEC A1–F9 boundary map (0–100, every threshold incl. E8) verified; DB-configured custom scale overrides fallback; CA weights default 20/20/60 + custom override verified (`GradingScaleTest`). |
| EXAM SCORING | **PASS** | Raw-score floor at 0, negative marking, percentage, empty-exam edge case verified (`ExamScoringTest`) against the new pure `util.ExamScoring` used by the exam submission path. |
| EXAM ELIGIBILITY / ATTEMPT LIMITS | PARTIAL | Logic lives inside JavaFX controllers (`ExamController`); no headless harness yet. |
| QUESTION BANK | PARTIAL | Static audits only: FXML↔controller↔handler wiring 0 problems across 42 FXML/71 Java (`tools/audit_fxml.py`, `tools/audit_deep.py`); no automated question-validation tests yet. |
| RESULTS / POSITION / CA AGGREGATION | PARTIAL | Result persistence + JOIN query verified at DB layer; position & CA aggregation embedded in controllers — not yet extracted for unit testing. |
| REPORTING (PDFs) | NOT VERIFIED | PDF generation requires the desktop/JavaFX runtime; cannot be exercised headless in CI. |
| OFFLINE CACHE | **PASS** | In-memory H2 offline bootstrap + seeded data verified. |
| SYNCHRONIZATION | PARTIAL | Replay/queue logic present (`SyncService`); not yet covered by automated tests. |
| PROCTORING | NOT VERIFIED | Focus-loss/3-strike logic is JavaFX-coupled and needs a live desktop run. |
| SECURITY (auth/audit) | PARTIAL | Lockout & code gates tested; WORM audit + RLS exist in SQL; compromised registration-code defaults **removed** — registration fails closed until codes are configured (`AuthService`). Remaining untested: proctoring paths (desktop-only). |
| BACKUP / RESTORE | NOT VERIFIED | `BackupService` present; AES-GCM paths need a runtime test. |
| PACKAGING (fat JAR) | **PASS** | Shaded JAR produced by CI. Zero-JDK `jpackage` Windows bundle is a tagged-release (`v*`) job — requires a Windows runner; not run this session. |
| DOCUMENTATION | UPDATED | This file + `docs/VERIFICATION-REPORT-2026-09-05.md`; README/guides describe the implemented system. |

## Summary

- **CI (compile + tests):** GREEN on the PR head for the first time since the
  repository received its workflow. Two P0 compile blockers and one P0 SQL
  blocker fixed and re-verified.
- **Automated tests:** 30 test methods (see `src/test/java`), all green in
  CI (`Tests run: 30, Failures: 0, Errors: 0`).
- **Schema gate:** a permanent "Schema migration check (local PostgreSQL)"
  CI job now executes the full 5-file Supabase migration chain on every
  PR/push.
- **External blocker:** the repo's Supabase DB secrets hold a host name that
  does not resolve in DNS (`could not translate host name`), which is why
  the `migrate` job is red. Schema content is fully validated by the new CI
  gate. See the BLOCKED section in `docs/VERIFICATION-REPORT-2026-09-05.md`.
