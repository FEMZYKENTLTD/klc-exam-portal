# KLC CBT SUITE v1.0 — ENGINEERING VERIFICATION REPORT

> **REMEDIATION ADDENDUM (2026-09-01, same day):** owner decisions received
> and implemented — see **§7** at the bottom. The matrix above reflects the
> code as first audited; §7 lists every follow-up fix shipped on this branch.

**Audit date:** 2026-09-01 · **Auditor:** Acting ML/Software Engineer, project owner of record
**Scope:** Every verifiable claim in `README.md` checked against the actual codebase at `4f98a18` ("Local updates: social upgrades") + fixes applied on this branch.
**Method:** Full source read of all 68 controllers/services, scripted cross-checks (`tools/audit_fxml.py`: FXML↔controller↔handler↔path integrity across 41 FXML files), schema cross-referencing against `supabase/*.sql` and `DatabaseInitializer`.

---

## 1. README CLAIM VERIFICATION MATRIX

| # | README claim | Verdict | Evidence |
|---|---|---|---|
| 1 | **3-Strike Proctoring** — focus loss detection, 3 violations → auto-submit | ✅ VERIFIED (now incl. lockout) | `FocusLossDetector` (3 strikes → `submitExam(true)`); `ExamController.startExam` disables it in practice mode |
| 2 | "blocks Alt+Tab, copy-paste, and screen capture" | ⚠️ PARTIALLY TRUE → **FIXED** | Alt-Tab/minimize is *detected* (not blockable on Windows). Copy-paste was **not** blocked anywhere — added scene-level Ctrl+C/X/V/A + context-menu blocking in `ExamController`. Screen-capture blocking is not implementable in pure JavaFX/Java — README overclaim, see §4 |
| 3 | 3 strikes → "auto-submit **and account lockout**" | ⚠️ → **FIXED** | Auto-submit worked; account lockout did not exist. Now locks the student 15 min on malpractice submit + audit `MALPRACTICE_LOCKOUT` |
| 4 | **Cloud + Offline Safe** — Supabase PostgreSQL + H2 fallback, auto-retry | ✅ VERIFIED | `DatabaseManager` (direct 5432 → pooler 6543 → H2, 8s timeout, 30s cloud re-check) |
| 5 | "answers save locally every 30 seconds" | ❌ FALSE → **FIXED** | Answers were only persisted on navigation/submit. Added 30s autosave `Timeline` in `ExamController` |
| 6 | "auto-sync when the network returns" | ❌ FALSE → **FIXED** | `SyncService.syncNow()` queried non-existent `created_at`/`synced_at` columns → threw (silently swallowed) **every cycle**; and it never replayed payloads, only marked rows synced. Rewritten: real replay of queued `attempt_answers` to cloud. Also, auto-sync ran **only on the admin dashboard** — now started for the duration of every exam |
| 7 | **WAEC/NECO Ready** — MCQ A–E, True/False, image items | ✅ VERIFIED | `ExamController` (A–E radios, TRUE_FALSE, image loading), `QuestionBank`/`QuestionEditor` |
| 8 | "LaTeX formulas" | ❌ NOT FOUND | No LaTeX rendering anywhere (only a plain-text formula sheet dialog). Remove claim or implement |
| 9 | "topic-by-topic performance breakdown" | ❌ NOT FOUND | No topic/tag concept exists in the schema or analytics. `AnalyticsController` aggregates scores only |
| 10 | CSV student import, auto admission numbers, Result PINs | ✅ VERIFIED | `StudentManagerController`, `RegisterController.suggestAdmissionNo`, PIN = `SURNAME+CLASS` in `AuthService.register` |
| 11 | PDF/DOCX question parsing (PRO tier) | ✅ VERIFIED | `PdfQuestionParser` (PDFBox 3), `DocxQuestionParser` (POI 5) + `QuestionImporterController` |
| 12 | CA Score Integration | ✅ VERIFIED | `CAUploadController` + `ca_scores` table (handler wiring fixed in 4f98a18) |
| 13 | Full Audit Trail (WORM — ENTERPRISE tier) | ⚠️ PARTIAL | `audit_logs` exists and is written broadly, but is **not WORM** (no append-only enforcement, no RLS/trigger protection on Supabase) |
| 14 | API Access (ENTERPRISE tier) | ❌ NOT FOUND | No API surface exists |
| 15 | Parent Portal (ESSENTIALS tier) | ❌ NOT FOUND | No parent role/portal in code |
| 16 | Multi-Campus Support (ENTERPRISE tier) | ❌ NOT FOUND | Single-campus (`school_profile` singleton) |
| 17 | Registration codes: Super Admin / Staff / Student | ✅ VERIFIED | `AuthService` static codes match README exactly; overridable via `config.properties` |
| 18 | Super Admin seeded login | ⚠️ UNVERIFIED | No seed/INSERT of `superadmin@knowledgeland.edu.ng` exists — account must be self-registered with the code. Publishing credentials in a public README is a security risk regardless (see §4) |
| 19 | Branding rule: FEMZYK on Splash + About only, **no watermark on official documents** | ❌ VIOLATED → **still open** | `TranscriptPdf` (line ~171) and `AttendanceSheetPdf` (line ~76) print "Powered by FEMZYK" **on official documents** — directly violating the README rule and their own inline comments. `ReportCardPdf`/`IdCardPdf`/broadsheets are clean |
| 20 | Webcam proctoring | ⚠️ WAS A STUB → now real for **registration passport** only | `WebcamProctorService` (exam webcam) is still a **no-op stub** (`start()` = log line, `captureNow()` = TODO) despite the `webcam-capture` dependency and pom. `RegisterController.captureWebcam` (new in 4f98a18) is a genuine webcam capture via reflection |
| 21 | "Zero Java/JDK required, native .exe" | ⚠️ UNVERIFIABLE IN REPO | Fat-JAR shading configured; **no jlink/jpackage module-path config** in `pom.xml` and no WiX/MSI packaging — no actual `.exe` is produced by this repo |

**Score: 8 verified / 5 false-or-missing (fixed or flagged) / 5 partial / 3 unverifiable-in-repo.**

---

## 2. DEFECTS FOUND & FIXED ON THIS BRANCH

### P0 — broke core promises
| Defect | Impact | Fix |
|---|---|---|
| `SyncService` queried `created_at`/`synced_at` — columns that exist in **no** schema | Offline sync silently never ran; README cloud/offline promise false | Query corrected; payload **replay** implemented (`applyToCloud`), rows only marked synced on success |
| Auto-sync only started on admin dashboard, never during exams | Exam-time offline answers never pushed automatically | `SyncService.startAutoSync(null)` started at exam start, stopped at submit |
| `MessagesController` used `addressee_id`; live cloud + `FriendsController` use `receiver_id` | **Messaging broken in cloud mode** (`column does not exist`) and in offline mode (friendship check always fails). 4f98a18 hotfixed Friends but forgot Messages | All queries + H2 DDL migrated to `receiver_id`; legacy-cache ALTER kept |
| Social tables (`user_profiles`, `friendships`, `messages`) existed in **no** Supabase migration and not in `DatabaseInitializer`'s PG path | On a clean cloud deployment all three social screens fail ("relation does not exist") | Tables added to H2 schema + `ensurePostgresColumns`, and `supabase/klc_supabase_schema_v1_0_social.sql` migration added for DBAs (incl. addressee→receiver rename guard + RLS) |

### P1 — crashes / dead code
| Defect | Impact | Fix |
|---|---|---|
| `twofa_setup.fxml` → `com.femzyk.klc.admin.TwoFaController` (class lives in `.auth`) | LoadException if ever opened | Controller package corrected |
| Exam: no copy-paste prevention (instructions even told students it was blocked) | Integrity claim false | Scene filters consume Ctrl+C/X/V/A + context menus |
| Exam: no periodic answer save | "saves every 30 seconds" false | 30s autosave timeline |
| Exam: no account lockout on 3rd strike | README claim false | 15-min lockout + audit event |

### P2 — hygiene
- `klc_assets/` (runtime photo/chat files) was committed incl. a personal photo → **removed from tracking, gitignored** (note: it remains in git history; see §5).
- `BackupService` manifest still says "Version: 6.2" (cosmetic leftover after v1.0 rebrand).
- `tools/audit_fxml.py` added as a permanent regression gate for FXML↔controller wiring.

---

## 3. NEW SOCIAL MODULE (4f98a18) — REVIEW NOTES

Good: parameterized SQL throughout, `setUuid` used consistently, friendship verified server-side before every send, 15MB attachment cap with filename sanitization, unread counts, 15s polling Timeline that self-stops, audit events on friend actions.

Concerns for a **secondary-school** product (recommend addressing before marketing to schools):
1. **Safeguarding:** students can search **all** users (teachers/admins included) and DM them privately, with attachments, no content moderation, no message audit (content lives outside `audit_logs`), no retention policy, no parental visibility. Recommend: student↔staff DM requires staff opt-in; message metadata audit; retention window.
2. **Exam integrity:** chat is reachable from the student dashboard at all times. Recommend a global "exam window" gate (e.g., `MainApp.examInProgress` blocks messaging, or disable social tabs during scheduled exams).
3. **Attachments don't actually transfer:** messages store the *sender's local path* (`klc_assets/attachments/...`). The receiver on another PC gets "File not found". Either upload to Supabase Storage or drop the feature from docs.
4. `loadFriends()` uses `OR` joins — fine at school scale, will not scale past thousands of friendships.

---

## 4. SECURITY & GOVERNANCE FINDINGS (no code change — decisions needed)

1. **README publishes live credentials** (super admin email/password + all registration codes). They are also hardcoded as *defaults* in `AuthService`. Any school deployment using defaults is compromised by design. Recommend: rotate the student/staff codes per school, seed super admin on first run with a forced password change, strip creds from README.
2. **`config.properties.example` is good** (placeholders only) and real config is gitignored — ✅ no leaked secrets found in history scan of tracked files.
3. **Login user enumeration**: failed-login errors are generic ✅, but lockout writes make timing/lock behavior observable. Acceptable for v1.
4. **Audit trail is not WORM** while the ENTERPRISE tier sells "Full Audit Trail (WORM)". Either add a Supabase trigger denying UPDATE/DELETE on `audit_logs` or rescope the tier text.
5. **Committed personal photo** (`klc_assets/profile_photos/63d00….jpg`, 182 KB) is now untracked, but **remains in git history** on `main`. If it's a real person's photo, consider history rewrite (`git filter-repo`) or accept + document.

---

## 5. RECOMMENDED README EDITS (not applied — marketing copy, owner's call)

- Remove or implement: "LaTeX formulas", "topic-by-topic performance breakdown", "API Access", "Parent Portal", "Multi-Campus Support" — none exist in code.
- Rescope: "blocks … screen capture" → "detects focus loss and blocks copy-paste"; webcam proctoring is registration-only today.
- Remove live credentials from the public README.
- Add the social module (Profile/Friends/Chat) to the feature list — it's the headline of the latest release and undocumented.
- "native .exe" — add the jpackage/jlink packaging config or say "Runnable JAR (bundled JRE installer)".

---

## 6. VERIFICATION GATES

- `python3 tools/audit_fxml.py` — **42 FXML / 70 Java files; 0 real defects** (remaining output lines are documented false positives: `load("admin_…")` prefix helper and `loadSocial()` prefix helper — both resolve correctly at runtime; LoginController teacher/exam-officer fallbacks are null-guarded).
- Brace/paren balance verified on all edited files. ⚠️ Full `mvn package` could **not** be executed in this sandbox (all Maven/JDK download hosts network-blocked); the new CI workflow (`Actions → Build`) now performs the authoritative compile on every push — owner should watch the first run.

---

## 7. REMEDIATION ADDENDUM — OWNER DECISIONS → SHIPPED FIXES

Owner directives: *maintain all features · do it the best way · "Powered by FEMZYK" stays · publish no credentials.*

| Directive | What shipped |
|---|---|
| **"Powered by FEMZYK" stays** | FEMZYK credit lines on Transcript/Attendance PDFs left untouched. README branding rule reworded to match reality (credit allowed on documents). No watermark claims removed. |
| **Credentials → secrets + rotation** | Full inventory + runbook in `SECURITY_CREDENTIALS.md`. Scrubbed from repo: super-admin password, **two** BCrypt hashes, project ref, and the **hardcoded Supabase anon key (JWT)** found in 3 controllers (ExamManager / LiveMonitor / ResultsView — worst find of the audit: anon key + RLS-disabled = effectively public DB). Realtime WS endpoints now config-driven (`supabase.url`/`supabase.key`), skip gracefully when absent. Super-admin H2 seed no longer uses a published password — reads `app.superadmin.password` or generates a random one-time password printed once. `config.properties.example` expanded (codes, flags, storage). `tools/generate_secrets.ps1/.sh` generate fresh codes + `gh secret set` commands. **Git history still contains the old values — rotation in Supabase is mandatory, not optional.** |
| **Safeguarding (student DMs)** | Four gates, all server-side: (1) chat locked school-wide while any exam window is active; (2) students cannot message staff accounts (config `social.allow_student_staff_dm=false` default); (3) students cannot send attachments (config `social.allow_student_attachments=false` default); (4) metadata-only audit on every message/attachment (`MESSAGE_SEND`/`MESSAGE_ATTACHMENT` — content never copied to logs). |
| **Attachments must transfer** | New `StorageService` (OkHttp → Supabase Storage, x-upsert, mime detection, 10s/60s timeouts). Sender: uploads and stores the public object URL; falls back to local path offline. Receiver: http URLs download before opening. Bucket `klc-attachments` provisioned by migration with public-read/auth-write policies. |
| **README claims with no code** | All six now have real backing: **LaTeX** → WebView+MathJax rendering in exams with offline plain-text fallback; **Topic-by-topic analytics** → topic tagged per question (editor had it; schema/PG alters added), breakdown on every student's result dialog + Analytics report; **Parent Portal** → `PARENT` role (family code, free), ward linked by admission no, read-only results screen (`parent_dashboard.fxml`); **API Access** → `API.md` (Supabase REST/PostgREST, views, RLS rules); **Multi-Campus** → `campus_name` on school profile + settings UI + per-campus deployment pattern documented; **WORM audit** → DB trigger making `audit_logs` INSERT-only (`klc_supabase_v1_1_security_and_features.sql`, which also re-enables RLS that `v6_3_final` had disabled). |
| **Auto-build on push** | `.github/workflows/build.yml`: every push/PR → JDK 17 + `mvn package` + JAR artifact; pushes to `main` publish a rolling `latest-build` pre-release; version tags (`v*`) additionally build a **zero-JDK Windows bundle** via `jpackage` (app-image zip) on a Windows runner. |
| **Other fixes in this pass** | `SyncService.flushOnCloudReturn()` — exam submit keeps retrying until every queued answer reaches the cloud; `ConfigService.get/flag()` config accessors; parent/registration flow updated end-to-end (role box, visibility, AuthService gate + `parent_profiles`, login routing); `questions.topic` + `school_profile.campus_name` ALTERs for H2 and Postgres; FXML audit re-run: **42 FXML / 70 Java / 0 real defects**; repo-wide secret sweep: **clean**. |

**Follow-ups left for owner (needs real credentials/machines):** rotate keys in Supabase (§2 of SECURITY_CREDENTIALS), decide on git-history rewrite, run first CI build, optionally restrict social module further per school policy.

