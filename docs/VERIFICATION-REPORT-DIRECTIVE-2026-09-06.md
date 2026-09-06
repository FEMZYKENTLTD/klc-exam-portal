# KLC CBT SUITE — DIRECTIVE-BUILD VERIFICATION REPORT (2026-09-06)

**Session:** "Full autonomous project owner — audit response, correction &
build directive" implementation. Branch `arena/01a072de-klc-exam-portal`
(PR #3, base `main`). Every claim below is backed by committed code and by
GitHub Actions runs of the PR (workflow "Build KLC CBT Suite" —
`Compile & package (Java 17)` + `Schema migration check (local PostgreSQL)`
jobs).

## 1. CI status at close

| Commit | Content | Compile+test | Schema gate |
|---|---|---|---|
| `b7e6459` | dark mode system + H2/backup test fixes | ✅ green | ✅ green |
| `c31d0c7` | schema inventory + matrix/acceptance refresh | ✅ green | ✅ green |
| `7066bb6` | toolbar labels + settings theme toggle | ✅ green | ✅ green |
| `db6f622` | Windows target matrix + web TXT/CSV load + audit in upload RPC | ✅ green | ✅ green |

Suite size: **56 JUnit test methods** across 10 classes (auth 14, exam
scoring 7, calendar 6, arm balance 6, catalogue 5, H2 AES 4, grading 4,
schema integrity, backup/restore 3, …) — all green on the PR head.

## 2. Directive items → what was built

| # | Directive item | Implementation | Test evidence |
|---|---|---|---|
| §8 | Real H2 AES at rest (`CIPHER=AES`) | `DatabaseManager`: `h2.encryption=aes` + `h2.cipher.filePassword` (≥16), `effectiveH2Url()` appends `CIPHER=AES`, password pair `filePassword userPassword`, one-time plaintext→AES SCRIPT/RUNSCRIPT migration with staged logs + delete-retry, fail-closed `getCacheConnection()` when AES requested without a key | `H2EncryptionTest` (file-backed): round-trip write→restart→read→modify→restart; no readable marker on disk; wrong key refused; migration preserves data; no plaintext open after migration; short key → no plaintext fallback file |
| §9/10 | Complete subject catalogue | `SUBJECT_CATALOG` 135 rows (19 per JSS level ×3 + 26 per SSS level ×3), always ensured at H2 startup; idempotent cloud mirror | `SubjectCatalogueTest` (coverage counts, code format, reseed idempotency) |
| §11 | Searchable subject inputs | `util.ComboSearch` (type→live filtered list, case-insensitive, no invalid free-text commit, FK-safe); wired into Exam Manager, practice picker, student dashboard | compile/wiring; util is deterministic (string-level) |
| §12 | Visible labels on all screens | Toolbar/filter/inputs across admin+student+social screens audited; labels added wherever placeholders were the only cue | FXML audit script: 0 label-less controls remain |
| §14 | Visual exam calendar | Exam Manager "📅 Exam Calendar" dialog: holiday-aware 42-day strip + ordered schedule; Nigerian statutory holidays + `calendar.holidays` config | `ExamCalendarTest`, `SchoolHolidays` |
| §15 | Class-arm auto balancing | `util.ArmBalancer` deterministic planner (minimum moves, admission-order); `ClassManagerController` Preview + Apply (transactional, audited per move) | `ArmBalancerTest` (6 cases incl. applying moves levels arms) |
| §16 | Practice solution review | Post-submit answer-review dialog (question, topic, your answer, correct answer, RIGHT/WRONG) gated to practice/mock only | controller logic; never shown for official exams |
| §17 | Distinct mock exam mode | `exams.is_mock` (H2+PG+cloud, `v_official_exams`), timed/exam-style, proctoring disabled, status `MOCK_SUBMITTED`, no `results` row, `[MOCK EXAM - NOT RECORDED]` labelling, dashboard mock offer path | schema/queries + data-rule assertions |
| §18 | Invigilator mobile page | `klc-web-admin/invigilator.html/.js` + `staff_live_room` RPC (bcrypt per call; official attempts; elapsed; strikes; auto-refresh) | schema gate runs the RPC DDL |
| §19 | Backup / restore / USB pack / PITR | `BackupService`: full-snapshot backup of every real table, AES-256-GCM optional, one-transaction `restore()` (FK suspension, full rollback), `exportUsbPack()` snapshot+SHA-256+manual; Backup screen buttons; Supabase Pro PITR honestly labelled | `BackupServiceTest`: round-trip (commas/quotes/newlines/NULLs), corrupt-file rollback, USB pack contents |
| §20 | Global dark mode | `util.ThemeService` + `klc-dark.css` appended last on every Scene; persisted `~/.klc_theme`; all 42 FXML screens migrated to theme classes; toggles on login, admin home, student dashboard, school settings | wiring; XML well-formed across all FXML |
| §21 | Web-admin question upload | `question_upload.html/.js` (staff login, subject/class, live parse preview, TXT/CSV file load) + `staff_upload_questions` RPC (bcrypt, teacher subject-ownership, `is_approved=FALSE`, options validation, audit row) | schema gate runs the RPC DDL; client parser deterministic |
| §22 | ~30-table authoritative schema | `docs/SCHEMA-INVENTORY.md`: 33 application tables, per-table purpose/PK/FKs/constraints | DDL extraction + inventory |
| §23 | Super-admin secure bootstrap | config/secrets-supplied email+password, BCrypt, cloud `ensurePostgresSuperAdmin`, seeded login test, no committed production password | `AuthServiceTest.seededOfflineSuperAdminCanLogin` |

## 3. Security notes

- Registration code gate preserved and fail-closed (codes come from config
  secrets only — never source/README/logs).
- No production credentials committed anywhere in this session; new config
  keys (`app.superadmin.*`, `h2.cipher.filePassword`, `calendar.holidays`)
  exist only in `config.properties.example` with blank values.
- Official school documents remain free of vendor credits; the backup
  manifest was made school-neutral too.

## 4. Remaining honest boundaries (not silent deletions)

- Windows x86 + Win7/8/8.1: preserved as targets; require an external
  x86/legacy build environment (Java 8 track for Win7/8; x86 Temurin JDK
  for 32-bit) — exact steps documented in `docs/INSTALLER_GUIDE.md`
  "Deployment-target matrix".
- OS-level kiosk lockdown (Task Manager / Alt-Tab blocking) is outside pure
  Java; detection + 3-strike auto-submit is implemented.
- Native Supabase PITR is a Pro-tier dashboard feature; the free-tier
  strongest recovery is the new restore/USB pack (transactional, tested).
- Desktop visual flows (JavaFX rendering of dark mode, calendar dialog,
  review dialog) are compiled + wired; pixel-level verification needs a
  desktop run.
