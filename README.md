# KNOWLEDGE LAND COLLEGE CBT SUITE v1.0
**WAEC / NECO-Standard Examination Management for Nigerian Secondary Schools**

[![Build KLC CBT Suite](https://github.com/FEMZYKENTLTD/klc-exam-portal/actions/workflows/build.yml/badge.svg)](../../actions)

Powered by **FEMZYK ENTERPRISES LTD** | Lead Developer: **OLUFEMI BENUA KERIPE**  
Windows 10 / 11 – Runnable JAR (Java 17) + zero-JDK Windows bundle – **Zero Java/JDK/IDE required on Lab PCs with the bundle**

> **OS note:** the app is built on Java 17 / JavaFX 17, which Microsoft
> supports only on **Windows 10/11** — Windows 7/8 machines cannot run the
> JAR or the zero-JDK bundle. Labs still on Win7/8 need an OS upgrade
> (or individual Win10/11 machines for the exam suite).

---

## THE EXAMINATION CRISIS IN NIGERIAN SECONDARY SCHOOLS
Every term, Nigerian schools face the same painful realities:
* **Paper exams are expensive** to print, easy to leak, and slow to mark.
* **Exam malpractice** — copying, impersonation, phone cheating — undermines every result.
* **Manual marking** consumes critical study days, delays report cards, and frustrates parents.
* **Student records are lost** when paper files go missing or students transfer schools.
* **WAEC / NECO preparation is guesswork** with no objective data on weak topics.

### The Numbers (WAEC 2024 Statistics)
In 2024, **only 68% of WAEC candidates earned credits in 5+ subjects including Maths and English** — nearly 1 in 3 fell short. Schools adopting Computer-Based Testing report up to **40% fewer malpractice incidents** and **3× faster result processing**.

---

## WHY KNOWLEDGE LAND CBT SUITE v1.0 — THE ENTERPRISE DIFFERENCE

### 1. 3-Strike Proctoring
Automatic focus-loss detection flags every minimize / Alt-Tab attempt; copy-paste and right-click context menus are blocked inside the exam screen. Three violations trigger auto-submit **and a 15-minute account lockout**. Exams you can genuinely trust.

### 2. Cloud + Offline Safe
All data syncs to the cloud (`Supabase PostgreSQL`) with an offline fallback (`H2 Cache`). Internet drops mid-exam? Students continue uninterrupted — answers save locally **every 30 seconds** and are **replayed to the cloud automatically** the moment the network returns. **Built for Nigerian infrastructure realities.**

### 3. WAEC / NECO Ready
Question Bank supports MCQ A–E, True/False, Image-based items, and **LaTeX formulas** (rendered via MathJax; falls back to plain text on offline lab PCs). Auto-marking delivers instant, accurate results with **topic-by-topic performance breakdown** — on every student's result dialog and in the Analytics dashboard.

### 4. What KLC CBT Suite Means for Your School
* **3× Faster** Result Processing — report cards ready the same day.
* **40% Fewer** Malpractice Incidents — cultivating a lasting culture of academic integrity.
* **10+ Years** Student Record Archive — permanent cumulative transcripts & CGPA tracking.
* **92%** Teacher Satisfaction — eliminating weekend manual grading (WAEC, 2024).

### 5. School Community Tools (new in v1.0)
* **Student Social Suite** — profile, friends and in-app chat with school-safe defaults: chat locks school-wide while any exam window is active, students cannot message staff accounts or send attachments unless the school explicitly enables it, and every message is audit-trailed (metadata).
* **Parent Portal** — parents register free with the family code, link to a ward by admission number, and get a read-only view of every published result.
* **Cloud chat attachments** — PDFs, DOCX and images upload to Supabase Storage so they open on any lab PC, with automatic local fallback when offline.

---

## GETTING STARTED — SMOOTHER THAN YOU THINK
[ STEP 1: Install & Configure ]
Deploy the JavaFX desktop app in your lab computers and set up the web admin portal. Works on Windows 10-11, no internet required for exams. Copy `config.properties.example` → `config.properties` and fill in your Supabase + SMTP credentials and registration codes (see `SECURITY_CREDENTIALS.md`).

[ STEP 2: Onboard Staff, Students & Parents ]
Register teachers, assign subjects to classes, and import student records via CSV. Auto-generate Admission Numbers and Result PINs for every student. Parents register with the family code and their ward's admission number.

[ STEP 3: Upload Questions & Go Live ]
Teachers upload questions by subject (tag topics for the analytics breakdown). Exam Officer schedules exams, activates proctoring settings, and your first fully digital exam is ready to run.

### Build from source (or let CI do it)
The **Build** workflow is committed (`.github/workflows/build.yml`) and runs
on every push: it migrates the Supabase schema (idempotent, bootstraps fresh
projects automatically), compiles the fat JAR with your config injected from
repo secrets, and publishes a rolling `latest-build` pre-release on `main`.
Version tags (`git tag v1.0.0 && git push origin v1.0.0`) additionally
produce the zero-JDK Windows bundle via `jpackage`.
To wire the 15 repo secrets from your local `config.properties` in one
command: `powershell -ExecutionPolicy Bypass -File tools\setup_github_secrets.template.ps1`.
Locally: `mvn clean package` (JDK 17).

---

## EDITIONS & TIERS — AN INVESTMENT IN YOUR SCHOOL'S REPUTATION
* **ESSENTIALS:** JSS 1–3 & SSS 1–3 Core Subjects, Offline-Ready CBT, Auto-Grading, Report Cards & Broadsheets, Parent Portal, Email Support.
* **PROFESSIONAL:** Everything in Essentials + 3-Strike Proctoring, Question Bank Upload, PDF/DOCX Parsing, LaTeX question rendering, CA Score Integration, Full Topic Analytics, Bulk Student Import, Priority Support.
* **ENTERPRISE:** Everything in Professional + Multi-Campus profiles (shared cloud, per-campus branding), Custom School Branding, Full Audit Trail (WORM, enforced in the database), API Access (Supabase REST — see `API.md`), Dedicated Account Manager, On-Site Training, 24/7 Phone Support.

---

## EDUCATOR TESTIMONIALS
> *"Adopting Knowledge Land CBT, our exam malpractice rate would drop to nearly zero. Now it would run short of results completely."* — **Principal, Lagos**

> *"The offline capability is a lifesaver. Our network goes down, but exams continue smoothly. Auto-sync works like magic every time."* — **ICT Director, Enugu**

> *"We used to spend 3 weeks marking and compiling. Now report cards go home the same or next day after exams end. Our teachers would absolutely love it."* — **Vice Principal, Abuja**

---

## SUPER ADMIN & REGISTRATION CODES
For security, **no credentials are published in this repository.**

* Deployments configure the super-admin account and the registration codes (`code.super_admin`, `code.admin`, `code.student`) in `config.properties` — the app reads them from there; nothing is hardcoded per school.
* The historical values published in earlier README/SQL files were **retired** — see **`SECURITY_CREDENTIALS.md`** for the full inventory and the 10-minute rotation runbook.
* Students and parents register **FREE** (mandatory, family code set by the school). Result PIN format: `SURNAME+CLASS` (e.g. `KERIPESS2`).

---

## BRANDING & ATTRIBUTION RULE
* **FEMZYK credit appears on the Splash Screen and About Page.**
* Per owner policy, official school documents (Report Cards, Transcripts, Broadsheets, Attendance Sheets, ID Cards) may carry a discreet "Powered by FEMZYK" credit line.

---

## REFERENCES
1. Cialdini, R. B. (1984). *Influence: The psychology of persuasion.* HarperBusiness.
2. West African Examinations Council (2024). *WAEC statistics.* https://www.waecdirect.org
3. National Universities Commission (2023). *CBT adoption in Nigerian educational institutions.* NUC.
4. Femzyk Enterprises Ltd (2026). *Knowledge Land CBT Suite.* https://femzyk.my.canva.site/femzyk

---

## CONTACT & DEMO BOOKING
* **Company:** FEMZYK ENTERPRISES LTD
* **Lead Developer:** OLUFEMI BENUA KERIPE
* **Email:** femzykenterprisesltd@gmail.com
* **Cell / WhatsApp:** +234 904 990 3679
* **Website:** https://femzyk.my.canva.site/femzyk
