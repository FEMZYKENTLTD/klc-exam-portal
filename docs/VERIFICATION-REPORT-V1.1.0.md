# KLC CBT Suite v1.1.0 — Interface Verification Report

**Date:** 2026-10-02 · **Method:** headless pixel-level verification
**Tool:** `tools/screenshot-harness/KlcShotHarness.java` (new, committed)
**Evidence:** 41 screenshots in `docs/screenshots/`, sample documents in
`docs/samples/`, harness log reproduced below.

This report closes the last honest boundary recorded in
`VERIFICATION-REPORT-DIRECTIVE-2026-09-06.md`: *"Desktop visual flows are
compiled + wired; pixel-level verification needs a desktop run."*

## 1. How it works

The harness boots the **real** `MainApp` on JavaFX's Monocle **headless**
graphics pipeline (pure software rendering — no display, X11 or GTK needed),
with the genuine config-loading, database and seeding paths:

1. App boots → H2 offline cache initialises (31 tables), 135-subject
   catalogue, school profile, seeded admin (the production bootstrap path).
2. Harness registers, through the app's own `AuthService` (BCrypt,
   student profiles, result PINs): 1 super admin, 1 teacher, 7 students
   (JSS1–SS3), 1 parent — then seeds classes, teacher assignments, a
   topic-tagged Mathematics question bank, two LIVE exam windows, 8
   completed attempts with published results, CA scores, announcements,
   a study material and an open appeal (plain H2 JDBC).
3. It then walks 41 screens exactly as the controllers do — including the
   exam screen via the real `ExamController.startExam(examId, variant)`
   entry point — snapshotting each scene (light + dark themes).
4. Finally it generates the official documents through the app's own
   services (`ReportCardService`, `GraduationCertificatePdf`) and verifies
   non-empty PDF output.

Demo config comes from an external `./config.properties` (the `AppConfig`
override path) — no repository secret is ever involved. The suite exits
non-zero on ANY load/render failure, so it is a repeatable regression gate.

## 2. Shipped bugs the harness caught (and their fixes)

| # | Severity | Bug | Impact before fix | Fix |
|---|---|---|---|---|
| 1 | **P0 — core feature dead** | `exam.fxml` declared `<ToggleGroup fx:id="optionsGroup"/>` as a **layout child** of the options `VBox`. A ToggleGroup is not a `Node`, so FXMLLoader threw *"Unable to coerce ToggleGroup to Node"* | **The exam screen could never load — for any student, ever.** Every exam launch crashed before the first question rendered | ToggleGroup moved to `<fx:define>`, radio buttons reference it via `toggleGroup="$optionsGroup"` |
| 2 | **P0** | `exam.fxml` root is a `BorderPane` but `ExamController.examRoot` was declared `VBox` | Second, independent exam-screen loader crash (*"Can not set VBox field examRoot to BorderPane"*) | Field re-typed `@FXML private BorderPane examRoot` |
| 3 | **P1** | Five FXMLs used `tooltip="text"` — not valid FXML (a string cannot coerce to `Tooltip`) | **Exam Manager screen failed to load** for admins; exam toolbar buttons dead | Converted to `<tooltip><Tooltip text="…"/></tooltip>` elements |
| 4 | **P2** | Light theme striped table rows had **no** row-background/text rule, so odd rows rendered white-on-white | Data present but invisible in every light-mode table (Student Manager, Results, Audit, Broadsheet…) | Explicit `.table-row-cell` / `.table-cell` / `:odd` / `:selected` colours in both themes |

Bugs 1–3 mean **v1.0.0 could not run an exam or open the Exam Manager at
all** — a reminder that compile-green is not runtime-green. All four are
fixed on this branch and proven by the shipped renders
(`40_exam_screen.png`, `19_admin_exam_manager.png`,
`12_admin_student_manager.png`).

## 3. Verified runtime behaviour (all against the real code)

- Splash → auto-advance → login (theme toggle state) ✔ `01–03`
- Registration & password-reset screens ✔ `04–05`
- All 27 admin screens load and populate ✔ `10–36`
- Dark theme on dashboard + student manager ✔ `37–38`
- Student dashboard: PIN/admission headers, results, announcements ✔ `39`
- Practice exam screen ✔ `39b`
- **Exam engine: live exam loads, question + A–E render, timer runs,
  proctoring strip present, question navigator built** ✔ `40`
- Social suite (profile / friends / messages) ✔ `41–43`
- Parent portal (ward results) ✔ `44`
- **PDF exports: report card (3.5 KB), transcript (3.5 KB), graduation
  certificate (1.7 KB) generated through the app's own services** ✔
  (`docs/samples/`)

## 4. Harness log (final run)

```
[harness] app booted - DB ready
[harness] registered SUPER_ADMIN admin@klc.test   (…6 more demo accounts…)
[harness] seed complete
[harness] wrote 41 screenshots (…)
[harness] PDF report card generated: ReportCard_…_1st_2025-2026.pdf (3509 bytes)
[harness] PDF transcript generated: Transcript_….pdf (3526 bytes)
[harness] PDF graduation certificate generated: shots/sample_graduation_certificate.pdf (1753 bytes)
[harness] done - ALL CLEAN - screenshots in shots        (exit code 0)
```

## 5. Reproducing / keeping the gate green

```
# after CI (Build KLC CBT Suite) or mvn package:
java -cp harness:knowledge-land-cbt-1.1.0.jar:openjfx-monocle-21.0.2.jar \
  -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw \
  -Dmonocle.screen.width=1440 -Dmonocle.screen.height=900 \
  KlcShotHarness screenshots
```

Any FXML/controller regression (new root-type mismatch, new non-Node
child, new tooltip misuse) fails the run with a named screen — pixel-level
verification is no longer a one-off exercise.
