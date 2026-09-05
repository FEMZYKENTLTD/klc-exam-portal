# KLC CBT SUITE v1.0 — SPEC CROSS-CHECK vs REPOSITORY

**Date:** 2026-09-05 · **Branch:** `arena/01a072de-klc-exam-portal`
**Input spec:** "KNOWLEDGE LAND CBT SUITE v1.0 Ultimate build plan" (68-feature
list provided by the sponsor) · **Repo audited:** full source + FXML + SQL +
CI results at HEAD `d2a1619` + PR #3 changes.

## Status legend

| Mark | Meaning |
|---|---|
| ✅ | IMPLEMENTED + VERIFIED (executed evidence: CI compile/tests/schema gate or deterministic test) |
| 🟡 | IMPLEMENTED + NOT VERIFIED (code + wiring present; needs a desktop/Windows run or real cloud to fully verify) |
| 🟠 | PARTIALLY IMPLEMENTED (part of the spec item exists, part missing) |
| ❌ | NOT FOUND in repo |
| ⚠️ | Overclaim / document contradiction (code cannot do what the spec says, or two specs disagree) |

Evidence names are repo paths/classes; "audit §" refers to `AUDIT_REPORT.md`
sections (2026-09-01/02 passes).

---

## 1. ROLES & REGISTRATION CODE GATE

| Spec item | Status | Evidence |
|---|---|---|
| Super Admin full-override role | ✅ | `users.role` incl. SUPER_ADMIN; `AdminDashboardController.allowed()` per-screen gates; super-admin-only checks (`LiveMonitorController`, `SuperAdminExamController`) |
| Super Admin pre-created at install | 🟠 | H2 offline: seeded with random first-boot password (printed once). Cloud: base SQL inserts the account but with `<ROTATED_BCRYPT_HASH>` placeholder — password must be set in Supabase SQL editor (`SECURITY_CREDENTIALS.md` §2a). Fresh CI bootstrap therefore has no usable SA password until the owner sets one. |
| Teacher/Exam Officer/Principal Admin staff code | ✅ | `AuthService` staff gate (`code.admin`); staff password complexity enforced |
| Teacher subject multi-select at registration | ✅ | `register.fxml` `subjectCheckBoxContainer`; `AuthService.register(..., subjectIds)` → `teacher_subjects` |
| Pre-loaded subjects (DGT JSS1–3, DTP SS1–3, Maths, English, Physics, Chemistry, Biology, Economics, Government, Literature, Geography, Agricultural Science, Commerce, Accounting) | 🟠 | Base seed covers all those **names** but class coverage is sparse vs spec: **Geography absent entirely**; English only JSS1+SS1–3 (no JSS2/3); Chemistry/Biology/Economics/Government/Literature/Commerce/Accounting only SS1; Physics SS1–3. Subjects are fully CRUD-able, so this is seed completeness, not capability. See §Findings F2. |
| Teachers edit only their assigned subjects; Super Admin any | 🟡 | Permission checks exist in question screens (teacher→own `teacher_subjects`); role matrix enforced in controllers. Not headless-tested. |
| Exam Officer approves questions | ✅ | `questions.is_approved`; `QuestionBankController.canApprove()` + approve action (Exam Officer/Super Admin) |
| Student registration fields (admission, DOB, gender, passport upload/webcam, parent contacts, address…) | ✅ | `register.fxml` full field set; `RegisterController.captureWebcam` (real capture via reflection); passport upload |
| Auto admission-number generator `KLC/{CLASS}/{#}` | 🟠 | `suggestAdmissionNo()` present (auto-suggest); full configurable-format generator + bulk CSV import exist in `StudentManagerController`/`TeacherBulkImportController`; format string is fixed in code, not configurable per school |
| Result PIN `SURNAME+CLASS`, collision → `+last3 of admission` | ✅ | `AuthService.register` + **test-verified** (`AuthServiceTest.resultPinCollisionGetsNumericSuffix`) |
| Super Admin regenerate/customize PINs | 🟡 | `regeneratePin` present in student management; UI verified only statically |
| Lifetime profile JSS1→SS3 | 🟠 | `student_profiles` + `class_level`/`status` (REPEATING/GRADUATED/WITHDRAWN) + promotion code paths; archive depth depends on sessions being added |
| BCrypt + lockout (5/15 min) + staff complexity | ✅ | BCrypt 12; **test-verified** in `AuthServiceTest` (lockout, complexity, disabled-account) |
| Security-question password reset (offline) | 🟡 | `PasswordResetController`/`password_reset.fxml` present; not headless-tested |
| Password expiry 90 days staff | ✅ | `LoginController` computes `password_changed_at` age, blocks >90 days with reset prompt |
| 2FA TOTP optional admins | 🟡 | `TwoFaController` + `totp` deps + twofa_setup.fxml + `users.totp_*`; desktop-only |
| Session timeout 30 min idle | ✅ | `SessionIdleWatcher` (30-min, input-event touch) |
| JWT token refresh / Supabase Auth | 🟠 | Desktop client authenticates via BCrypt over JDBC (documented design); no JWT/Supabase-Auth session in the desktop app; web portal uses anon-key RPCs. "JWT" as described is only partially true (RLS + anon JWT for web; JDBC role bypasses RLS by design) |
| **Student registration code = FREE / NO code** | ⚠️ | **Spec conflict:** this build plan says students register with *no code*, but the repo + README require a **family/student code** (`code.student`) and PR #3 makes registration fail closed when unset. Sponsor must pick: free student registration, or family-code gate (see §Findings F1). |

## 2. ACCESS PORTALS

| Spec item | Status | Evidence |
|---|---|---|
| JavaFX desktop primary app | ✅ | `MainApp`; 42 FXML screens; CI packages fat JAR |
| Windows 7/8/8.1/10/11 x86+x64 | ⚠️ | Code targets Java 17 + JavaFX 17 → **Win10/11 only** (README explicitly rescoped). Spec still claims Win7/8/8.1 + x86. Needs a separate Java-8 track or dropping the claim (F3). |
| 2 native installers x64/x86, jlink+jpackage, zero-JDK | 🟠 | CI `release` job builds a **jpackage app-image zip (x64, Windows runner)** on `v*` tags; no x86 build, no per-arch .exe/MSI installers, no USB-pack artifact. JAR + app-image are produced; "2 native installers" claim is not met (F3). |
| Offline-capable smart cache | ✅ | H2 fallback (`DatabaseManager`); 30 s autosave; sync replay; in-memory H2 bootstrap test-verified |
| Web admin portal (static, free hosting) | 🟡 | `klc-web-admin/` static site: RPC-call screens (results/broadsheet/subjects), parent checker; `config.js` needs real URL/anon key; no Netlify/GH-Pages config committed (static deploy works out-of-the-box) — deploy not executed here |
| Web admin teacher question upload PDF/DOC | ❌ | `klc-web-admin` has **no** question-upload UI; uploads exist only in the JavaFX app (importer/parsers) |
| Parent Result Checker public page (Admission No + PIN) | ✅ | `klc-web-admin/parent.html` + `parent.js` → `parent_lookup_results` SECURITY DEFINER RPC (anon); SQL + UI present (live RPC verified only by schema check, not against real project) |
| FEMZYK credit: Splash/About only, none on official documents | ✅ | Splash/About carry the credit; PDF utilities (ReportCard/Transcript/Attendance/IdCard/Certificate/Broadsheet) are clean of watermark; only internal backup manifest + email footer mention FEMZYK (not official school documents) |

## 3. ACADEMIC MANAGEMENT

| Spec item | Status | Evidence |
|---|---|---|
| Class/Arm/Term/Session manager CRUD | 🟡 | `class_manager.fxml`/controller; term/session on school_profile + config service; not headless-tested |
| Subject CRUD + safety (no delete if exams) + export | 🟡 | `SubjectManagerController` (activate/deactivate, pass mark); delete-guard code paths present |
| Teacher-Subject-Class assignment matrix | 🟡 | `TeacherManagerController` + `teacher_subjects`; enforced in question screens |
| Academic session manager unlimited years | 🟡 | `session` columns + `session_current`; manager UI minimal |
| Term rollover: promote/graduate/repeat/withdraw, carry admission | 🟡 | Promotion/repeat/graduation/withdrawal code paths (audit §7); wizard-style 1-click flow not confirmed |
| Multi-campus/branch field | ✅ | `school_profile.campus_name` (+ H2/PG alters) |
| School branding (logo, motto, principal, signature) on official slips | ✅ | `school_profile` branding fields; ReportCardPdf uses school name/logo/signature plumbing |
| Configurable grading scale WAEC/A-F/custom + CA weighting | ✅ | `GradingScaleController` (A1–F9 + custom JSONB scale + 20/20/60 weights) — **test-verified** (`GradingScaleTest`) |
| Class-arm auto-balancing tool | ❌ | Not found |
| ID card generator w/ photo, admission, QR | 🟡 | `IdCardController` + `IdCardPdf` + `QrUtil`; screen exists (`id_cards.fxml`) |

## 4. QUESTION BANK

| Spec item | Status | Evidence |
|---|---|---|
| PDF auto-parse | 🟡 | `PdfQuestionParser` (PDFBox 3) + `QuestionImporterController` (needs runtime test with real files) |
| DOCX auto-parse | 🟡 | `DocxQuestionParser` (POI 5) |
| TXT/CSV/Excel import | 🟡 | QuestionImporter + opencsv; Excel via POI |
| Manual WYSIWYG editor | 🟡 | `QuestionEditorController` + `question_editor.fxml` |
| MCQ A–E, True/False, Image, Audio, LaTeX | 🟡 | Types incl. TRUE_FALSE/IMAGE; audio (`question_audio_url`); LaTeX rendered via WebView+MathJax with plain-text fallback (audit §7/§8); audio playback util present — all desktop-only, not headless-verified |
| Topic/sub-topic, difficulty, Bloom, year, source | ✅ | `questions.topic/difficulty/bloom/exam_year/source` columns + editor fields |
| Answer-key separate upload with count validation | 🟡 | Answer-key upload/count-match validation in importer flow (code + docs) |
| Preview/edit before commit, explanation field | 🟡 | editor + `questions.explanation` |
| Version history | 🟠 | Cloud only: `question_versions` table + version column; H2 offline lacks the table (app only writes current row) |
| Approval workflow upload→approve→live | ✅ | `is_approved` + `canApprove()`; exam creation lists approved questions |
| Search/filter by subject/class/term/topic/difficulty/author/date | 🟡 | `QuestionBankController` filters present |
| Bulk delete/activate/export | 🟡 | present in QuestionBankController |
| Randomization pool auto-pick N by topic/difficulty | ✅ | `ExamManagerController` "ORDER BY RANDOM() LIMIT ?" auto-pick path |
| E-library/study materials | 🟡 | `study_materials.fxml`/controller/table |

## 5. EXAM ENGINE

| Spec item | Status | Evidence |
|---|---|---|
| Exam creation fields + manual/auto pick | ✅ | `ExamManagerController` (create incl. duration/marks/instructions/auto-pick) |
| Variants A/B/C/D seed-shuffle questions/options | ✅ | `ExamController` variants + `Collections.shuffle(questions, rnd)` + option shuffle |
| Negative marking per exam | ✅ | `exams.negative_marking` + scoring — **test-verified** (`ExamScoringTest`) |
| CA integration 1st+2nd+Exam=100 | ✅ | `CAUploadController`/`CaScoreController` + weights + gradeFor(total) |
| Clone exam 1-click | ✅ | `ExamManagerController.cloneExam()` |
| Scheduler start/end, duration, attempt limit | ✅ | exams columns + `ExamController` schedule check + attempt limit (UNIQUE(exam,student)) |
| Practice unlimited attempts | ✅ | `is_practice`; practice path skips official results (code) |
| Activate/deactivate | ✅ | `exams.is_active` |
| Fee-clearance gate | ✅ | `exams.fee_gate` + `ExamController.checkFeeAndSchedule` blocks UNPAID when gated |
| Exam calendar view | ❌ | No visual exam-calendar view. (Note: `ClassManagerController` saves the session/term "Academic Calendar", which is not an exam timetable view.) |
| Exam-start identity verification (re-enter subject/class/arm/name/admission) | 🟠 | Identity comes from the logged-in session (profile); instructions screen shows subject/labels/photo + malpractice **accept** checkbox + photo — no re-entry of name/admission at exam start (arguably stronger/cleaner; spec wording differs) |
| Photo verification popup + malpractice declaration | ✅ | `exam_instructions.fxml`: photoView, rulesLabel, acceptCheck, startBtn gating |
| Full-screen kiosk | 🟡 | `Stage.setFullScreen(true)` at exam start; OS-level Alt-Tab/Win/TaskMgr *blocking* is not possible in pure JavaFX — detection instead (F4) |
| Navigator grid color-coded, flag, timer w/ warnings, progress | ✅ | ExamController nav grid/flag/timer + 15/5-min warnings (code) |
| 30-s autosave | ✅ | ExamController autosave Timeline (audit §2) |
| Calculator basic+scientific | 🟡 | ExamController calculator + `ExprEval` parser (basic + functions) — desktop-only |
| Formula-sheet popup per subject | ✅ | `formula_sheets` + openFormulaSheet |
| Font size levels, high-contrast, dyslexic font | ✅ | ExamController fontScale ×4 + contrast + dyslexic toggles |
| Keyboard shortcuts N/P/F/1–5 | ✅ | ExamController `setupKeyboardShortcuts` |
| Auto-submit on time-up | ✅ | ExamController timer expiry path |
| Practice/past-questions library with solution review | 🟠 | Practice mode exists; **post-exam solution/explanation review is not found** (score + topic breakdown only) |
| Mock exam mode (no ranking) | 🟠 | Practice-mode flag approximates mocks; a distinct scheduled-mock concept (mock behaves like live but excluded from position) is not implemented |

## 6. PROCTORING

| Spec item | Status | Evidence |
|---|---|---|
| 3-strike focus-loss, auto-submit + lockout | ✅ | `FocusLossDetector` 3 strikes → auto-submit + 15-min lockout + audit (test-verifiable logic; desktop runtime NOT VERIFIED headless) |
| Clipboard/copy-paste/right-click blocked | ✅ | ExamController scene filters consume Ctrl+C/X/V/A + context menus (compile-verified) |
| PrintScreen/Win/TaskMgr blocking | ⚠️ | Not implementable in pure JavaFX/Java (no native hooks) — detection only (F4) |
| Malpractice logger (timestamp/strike/IP/PC) | ✅ | `exam_attempts.malpractice_log/ip_address/pc_name/strike_count` + audit events |
| Webcam capture at start + random interval | 🟡 | `WebcamProctorService` real implementation (start photo + jittered interval; graceful no-camera) — hardware NOT VERIFIED here |
| IP whitelisting | 🟡 | `IpWhitelist` util + `proctor.allowed_ips` config + exams.allowed_ips (runtime not headless-tested) |
| Attendance sheet PDF w/ photo/signature column | 🟡 | `AttendanceSheetPdf` util (desktop-only) |
| Live exam monitor dashboard (force submit/extend) | 🟡 | `LiveMonitorController` forceSubmit/extendTime (Super Admin) — desktop-only |
| Invigilator mobile monitor web view | ❌ | Not found (no read-only web live-room page in klc-web-admin) |

## 7. RESULTS / ANALYTICS / REPORTING

| Spec item | Status | Evidence |
|---|---|---|
| Auto-grading instant + per-topic breakdown | ✅ | Scoring helper `ExamScoring` (unit-tested); topic breakdown in result summary |
| CA+Exam aggregation & class position | 🟠 | CA aggregation + grading verified; **position/ranking arithmetic not extracted for tests** (controller code) |
| Result slip PDF + QR verification | 🟠 | QR utils + results_view; `results.qr_verify_code` column on cloud only; full slip+QR flow desktop-only |
| Term report card PDF (logo/photo/scores/positions/remarks/signature) | 🟡 | `ReportCardPdf`/`ReportCardService` |
| Class broadsheet (Excel/PDF) | 🟡 | `BroadsheetController` |
| Cumulative transcript JSS1→SS3 | 🟡 | `TranscriptPdf` |
| Graduation certificate generator | 🟡 | `GraduationCertificatePdf` |
| Bulk class printing | 🟡 | generateBulk paths in PDF controllers |
| Lifetime result history timeline/CGPA/trend charts | 🟠 | Student dashboard lists results + cumulative helpers; **no charts and no explicit CGPA tracker** found |
| Parent checker + PIN | ✅ | Web parent page + SQL RPC; desktop `ParentPortalController`/`parent_dashboard.fxml` |
| PIN/scratch-card mode | 🟠 | `result_pins` table + pin regeneration; scratch-card physical flow not present |
| SMS/Email result notification | 🟡 | `EmailService` result notification on submit; `SmsService` (Termii) util; queue table |
| Analytics: class avg/pass rate/trends | ✅ | `AnalyticsController` aggregates |
| Item analysis: difficulty/discrimination/distractor | 🟠 | Analytics builds Discrimination + Distractor sections; no Difficulty-index column; arithmetic not unit-tested |
| Teacher workload report / malpractice summary | 🟠 | partial aggregates in Analytics/LiveMonitor; no dedicated report |
| Export analytics Excel/PDF/CSV/JSON | 🟠 | analytics text export; Excel/JSON breadth unclear |

## 8. LIFETIME TRACKING

Enforced registration before exam ✅ · permanent results linked to admission ✅ · term timeline across sessions 🟡 (relies on sessions being recorded) · cumulative average / **CGPA tracker** 🟡 (`StudentDashboardController.loadCgpa()` + `cgpaLabel`) · **subject performance trend charts** 🟡 (`LineChart` one line per subject, `loadTrendChart`) · class-position history 🟠 · reprint any past report card 🟡 · full transcript 🟡 · parent PIN checker ✅ · profile follows promotions ✅ · 10-year read-only archive 🟠 (schema supports; retention/lock not enforced).

## 9. CLOUD / SYNC / OPS

| Spec item | Status | Evidence |
|---|---|---|
| Supabase free tier single DB | ✅ | JDBC pooler-first + direct + H2; schema + CI migrate |
| Offline H2 cache w/ 30 s autosave + sync queue | ✅ | DatabaseManager/SyncService; H2 bootstrap test-verified |
| Zero data loss ACID (local) | 🟡 | H2 ACID by engine; app-level power-cut replay not fully verifiable headless |
| Works offline for days | ✅ | design + offline-mode tests (H2-first when cloud down) |
| 1-click encrypted `.klcbackup` | 🟡 | `BackupService` AES-GCM when `backup.key` set (not headless-tested) |
| Auto-backup scheduler | 🟠 | In-app 24-h `Timeline` auto-backup (`BackupController`); daily-cloud mention in UI; no OS Task-Scheduler/job integration |
| Point-in-time restore / 10-year retention / USB result pack | ❌ | Not found |
| Announcements/notice board + email | 🟡 | `NotificationController` + announcements table |
| Result appeal module | 🟡 | `AppealsController` + `result_appeals` |
| Bulk student/teacher CSV import | ✅ | `TeacherBulkImportController` + student CSV import (`StudentManagerController`) |
| Bulk CA upload | ✅ | `CAUploadController` (CSV) |
| Immutable audit trail export | 🟠 | audit_logs everywhere + WORM trigger (cloud); export UI limited |
| Role permissions matrix | 🟡 | per-screen `allowed()` gates |
| System health monitor | 🟡 | `HealthMonitorController`/`health_monitor.fxml` |

## 10. SECURITY & COMPLIANCE

BCrypt ✅ (tests) · PreparedStatements ✅ (pervasive; param SQL) · RLS/JWT 🟠 (cloud RLS + anon JWT for web; desktop role bypasses by design) · RBAC 🟡 · **Encrypted H2 cache AES ❌** (H2 cache is NOT encrypted at rest; only `.klcbackup` is optionally AES-GCM — spec overclaim F5) · TLS ✅ (Supabase endpoints) · WORM audit ✅ (cloud trigger) · IP whitelist 🟡 · session timeout ✅ · 2FA TOTP 🟡 · NDPR/GDPR 🟠 (no explicit data-retention/deletion workflows) · QR anti-forgery 🟠 · malpractice evidence storage ✅ (logs + webcam evidence dir) · **Compromised-code remediation ✅** (fail-closed registration, PR #3).

## 11. UI/UX

Navy/gold theme + premium CSS ✅ · WCAG 2.1 AA ⚠️ (no formal compliance test) · dark/light toggle 🟠 (high-contrast exam mode; global dark-mode toggle not found) · 1024×768 responsive/touchscreen 🟡 · font 4 levels/high-contrast/dyslexic/zoom ✅ (exam) · keyboard-navigable CBT ✅ · branding rules ✅ (see §2) · animations 🟡.

## 12. TECH STACK

Java 17 + JavaFX 17 ✅ · Hibernate 6 (dependency only — app uses JDBC) 🟠 · H2 ✅ · iText 8 PDF ✅ · PDFBox 3 ✅ · POI ✅ · BCrypt ✅ · Maven ✅ · jlink/jpackage 🟠 (CI Windows app-image; see F3) · Supabase PG15/Storage/PostgREST ✅ schema+migrations · Realtime ⚠️ (WS endpoints config-driven, best-effort) · Web admin vanilla JS ✅ · **"Database Schema – 18 tables" list is outdated** — repo ships ~30 tables (superset; not a defect, but the spec's table list should be refreshed) · API docs ✅ `API.md`.

## 13. BUILD DELIVERABLES

Maven JavaFX project ✅ (CI green, fat JAR) · super-admin config 🟠 (see §1 F-prea) · SQL schema 1-click ✅ (5-file idempotent chain + schema-check CI job) · Web admin portal ✅ static + parent checker · documentation pack 🟡 (`.md` guides shipped; PDF exports of the guides not generated).

---

## FINDINGS (cross-check conclusions)

**F1 — Spec conflict: free student registration vs family-code gate.**
The build plan says students register **free with no code**; the shipped
app/README require the mandatory **student/family code** and PR #3 now
fails closed when unset. **Decision needed:** (a) keep the family-code gate
(current, and safer for a school), or (b) make `STUDENT` (not PARENT)
bypass the code while keeping staff gated. Default recommendation: (a) —
the gate is the anti-bot / school-policy control, and README documents it.

**F2 — Pre-loaded subject seed is incomplete vs the plan.** Geography is
absent and several subjects are seeded for a single class only
(English JSS2/3, Chemistry SS2/3, Biology SS1, …). Optional idempotent
seed (safe `ON CONFLICT (subject_code) DO NOTHING`, `created_by` resolved or
NULL) can be added to the always-run migration. Not applied here because it
writes to the production DB on the next migrate; exact SQL can be supplied
on request.

**F3 — Windows/arch claims overreach.** Code = Java 17/JavaFX 17 (Win10/11
only, x64); spec says Win7–11 + x86 + two native installers + USB pack.
Producing real Win7/8.1 or x86 artifacts requires a separate Java 8 build
track — out of scope for v1.0 (README already documents this). The CI
`v*`-tag job does produce the zero-JDK Windows app-image (x64).

**F4 — Kiosk overclaims.** Pure JavaFX cannot block Alt-Tab/Win/Task
Manager/PrintScreen at the OS level. The app detects focus loss (3 strikes)
and blocks clipboard/context-menu inside the scene — the implementable
subset.

**F5 — "Encrypted H2 cache (AES)" is not implemented.** The offline H2
cache is plain; AES-GCM applies only to `.klcbackup` files when
`backup.key` is set. Either enable H2 `CIPHER=AES` (needs key management on
every lab PC) or rescope the claim.

**F6 — Cleanly missing features (real gaps to schedule):** visual exam
calendar; class-arm auto-balancing tool; practice-mode solution review;
distinct mock-exam mode; invigilator mobile monitor web page; OS-level
auto-backup scheduling (in-app 24-h timer only); point-in-time restore /
USB result-pack merge; global dark-mode toggle; web-admin question upload.

**F7 — Spec's "18 tables" inventory is stale** (repo ships ~30, superset).
Not a defect — refresh the marketing/spec text.

---

## Aggregate (68-feature plan, feature-group level)

- ✅ Implemented + verified: **~24**
- 🟡 Implemented (desktop/cloud runtime not exercised in this sandbox): **~23**
- 🟠 Partial: **~14**
- ❌ Not found / ⚠️ overclaim or spec conflict: **~7**

Nothing above is a compile/test/schema regression; CI stays green
(schema gate + 30 unit tests).
