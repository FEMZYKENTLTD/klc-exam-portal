# KNOWLEDGE LAND COLLEGE CBT SUITE v6.3
**Secondary School Enterprise – Cloud Online / Auto-Sync – Lifetime Student Tracking**

Powered by FEMZYK | Lead Developer: **OLUFEMI BENUA KERIPE**

Windows 7 / 8 / 10 / 11 – x86 + x64 native .exe – **Zero Java/JDK/IDE required**

---

## Super Admin Login
```
Email: superadmin@knowledgeland.edu.ng
Password: Femi2022-
Code: FEMZYK ENTERPRISES LTD
```

Registration Codes:
- Super Admin: `FEMZYK ENTERPRISES LTD`
- Teacher/Admin/Exam Officer: `FEMZYK`
- Students: **FREE – MANDATORY** – No exam without registration

Result PIN: `SURNAME+CLASS` e.g. `KERIPESS2`

---

## Quick Start – 5 min – $0/mo

### 1. Supabase – FREE
https://supabase.com → New Project → `klc-cbt`
SQL Editor → Run:
1. `supabase/klc_supabase_schema.sql`
2. `supabase/klc_supabase_schema_v6_2.sql`

Project Settings → API → copy URL + anon key

Edit `src/main/resources/config.properties`:
```
supabase.url=https://aqircycpctadgvbqsadf.supabase.co
supabase.key=YOUR_ANON_KEY
supabase.db.url=jdbc:postgresql://db.aqircycpctadgvbqsadf.supabase.co:5432/postgres
supabase.db.user=postgres
supabase.db.password=KlcFemzyk2025!
```

### 2. Run JavaFX
```bash
cd knowledge-land-cbt
mvn clean javafx:run
```
Login: `superadmin@knowledgeland.edu.ng` / `Femi2022-`

### 3. Web Admin – FREE Netlify
```
cd klc-web-admin
# edit assets/config.js – paste Supabase URL + anon key
npx netlify deploy --dir .
```
Result Checker: `https://your-site.netlify.app/result-check.html`

### 4. Build Windows Installer
```bash
# x64
build_windows_x64.bat
# Output: KnowledgeLandCBT-6.3.0.exe

# x86
build_windows_x86.bat
# Output: KnowledgeLandCBT-x86-6.3.0.exe
```
Java Runtime **bundled** – school PCs need NOTHING installed.

---

## Features – 77 Enterprise – v6.3 COMPLETE

**Auth & Roles**
- Super Admin OLUFEMI BENUA KERIPE – full override, upload for any teacher, assign subjects
- Teacher/Admin – Code FEMZYK – mandatory subject selection
- Student Registration – FREE, MANDATORY – Admission No, Passport Photo (upload/webcam), Gender, Parent Phone, Result PIN auto-generated `SURNAME+CLASS`
- BCrypt + brute-force lockout (5 attempts / 15 min)
- Security Question password reset – offline friendly
- Password strength meter – complexity policy
- Session idle timeout – 30 min auto-logout
- 2FA TOTP library included – toggle in config

**Academic Management**
- Classes: JSS1-SS3 – Full CRUD
- Arms: A/B/C/Science/Art/Commercial – Full CRUD
- Terms: 1st/2nd/3rd – Session Manager unlimited years (2024/2025 → 2035+)
- Subject Management CRUD – Name, Code, Pass Mark, Teacher assignment
- Teacher-Subject-Class Assignment Matrix
- Term Rollover Wizard – Promote / Graduate / Repeat – 1 click
- School Profile Branding – Name, Logo, Motto, Principal Name/Signature – appears on ALL reports
- **Grading Scale Configurator** – WAEC A1-F9 editable, CA1/CA2/Exam weighting configurable – saved JSONB
- ID Card Generator – CR80 PDF with QR + Passport Photo

**Question Bank**
- PDF auto-parse (PDFBox) – DOCX auto-parse (POI) – TXT/CSV/Excel import
- Manual Entry – Rich editor – Image/Diagram upload – LaTeX Math / Unicode formulas
- Question Types: MCQ A-E, True/False, Image-based
- Topic / Difficulty / Source tagging – WAEC/NECO past q tagging
- **Answer Key Upload MANDATORY – validated – Question count MUST = Answer count or REJECT**
- **Approval Workflow: Teacher Upload → Exam Officer Approve → Live**
- Teacher = own subjects only | Super Admin = ANY subject
- Search/Filter by Subject/Class/Term/Topic/Difficulty/Author/Approval
- Question Version History
- **E-Library / Study Materials** – Upload PDF lesson notes per Subject/Class

**Exam Engine**
- MCQ A-E, True/False, Image-based – Auto-marking
- **Exam Variants A/B/C/D – shuffle questions + shuffle options per student – answer-key remapping – scoring correct**
- **Question Random Pool – Auto-pick N random by Topic/Difficulty**
- Practice Mode – unlimited attempts – vs Live Exam Mode – 1 attempt
- Mock Exam Mode
- Exam Scheduler – Start/End DateTime – enforced at login
- **Exam Instructions Accept Page – Malpractice Declaration – must accept before start**
- Exam Start Verification: Subject + Class + Arm + Full Name + Admission No + Photo check
- Auto-save answers every 30 seconds – power outage safe
- **Built-in CBT Calculator – scientific**
- **Formula Sheet popup – per Subject – configurable in DB**
- Flag-for-Review + Question Navigator Grid – color coded
- Countdown Timer – 15/5 min warning
- **Font size control – 4 levels**
- **High-Contrast Exam Mode**
- **Dyslexic-friendly font toggle**
- Keyboard shortcuts: N=Next, P=Previous, F=Flag, 1-5=Option A-E
- Touchscreen friendly
- **Negative Marking – configurable per exam – applied in grader**
- CA Integration: CA1 20% + CA2 20% + Exam 60% = 100% – **weights configurable**
- Clone Exam – duplicate to new Term/Session – 1 click

**Proctoring – 3-Strike**
- Full-screen kiosk lock – blocks Alt+Tab / Win key
- Minimize / Focus Loss Detection:
  Strike 1 = Warning
  Strike 2 = Final Warning  
  Strike 3 = Auto-submit + account lockout
- Clipboard / Copy-Paste / Right-click / PrintScreen blocked
- **Webcam Photo Capture – at exam start + every 60s – optional toggle – saves to `proctoring/{admission_no}/`**
- Malpractice Incident Logger – timestamp, IP, PC name
- **Exam Attendance Sheet PDF – auto-print – Name, Admission No, Photo placeholder, Signature column**
- Live Exam Monitoring Dashboard – Students online, time remaining, questions answered, strikes – Force submit / Extend time – Super Admin
- IP Whitelisting – config flag – restricts to Lab PC IPs

**Results & Reporting – KLC Official – NO FEMZYK watermark**
- Instant auto-grading – MCQ auto-mark – score breakdown per Topic
- CA + Exam Aggregation – configurable weighting
- Class Position / Ranking – auto-calculated
- **Official KLC Term Report Card PDF**
  School Logo, Student Passport Photo, All Subjects, Scores, Grade, Position, Teacher Remark, Principal Remark, **Principal Signature Image: OLUFEMI BENUA KERIPE**, **QR Code verification**
  **NO FEMZYK watermark**
- **Cumulative Transcript – JSS1 → SS3 – with CGPA**
- **Graduation Certificate Generator – SS3 completers**
- Class Broadsheet – Master Sheet – **Excel Export + PDF Export**
- Result PIN: `SURNAME+CLASS` e.g. `KERIPESS2` – auto-generated, collision handling, Super Admin regenerate
- Parent Result Checker – Web – Admission No + PIN – QR verification
- Print-ready – bulk print entire class
- **Result Appeal / Complaint Module** – Student submits → Admin resolves → audit logged

**Enterprise Cloud – $0/mo**
- Cloud Online-First – Supabase PostgreSQL Free – 500MB, 50k MAU
- Smart Offline Cache – H2 – answers auto-save locally every 30s
- Internet drops mid-exam → continue uninterrupted
- **Auto-sync every 30s** – SyncService – zero data loss
- App close / Power outage = zero data loss – ACID
- Live Exam Monitoring Dashboard
- Bulk Student CSV Import + Auto Admission No generator – `sample_students.csv`
- Bulk Teacher CSV Import – `full_name,email,subjects`
- Bulk CA Score Upload – CSV – `sample_ca_scores.csv`
- School Profile Branding – logo, signature – upload in School Settings → stored `klc_assets/`
- Configurable Grading Scale – A1-F9 – stored JSONB
- Announcements / Notice Board – push to Student Dashboard – read receipts
- **Audit Trail – immutable – WORM – every action logged – CSV Export**
- **1-Click Backup + Auto-backup scheduler**
  Encrypted `.klcbackup` ZIP – users, students, questions, exams, results, ca_scores – SHA256 checksum – `backup_logs` table
  Auto-backup: In-app daily toggle + Windows Task Scheduler: `KnowledgeLandCBT.exe --backup`
- **System Health Monitor** – Cloud DB status, Cache status, Email/SMS status, Disk free, Uptime, Live sync log
- Role-based Permissions Matrix
- About Page – FEMZYK credit – Splash Screen – version 6.3
- Result PIN / Access Code system
- **Email / SMS Result Notification**
  SMTP: Jakarta Mail – `smtp.host/user/pass` in config.properties
  SMS: Termii Nigeria – `sms.provider=termii / sms.api_key=` – free tier
  Auto-send after exam submit – queue fallback if not configured
  Bulk "Notify All Recent Results" button

**UI/UX**
- Premium KLC Navy / Gold theme – WCAG 2.1 AA
- Dark / Light mode CSS ready
- Card-based dashboards
- Responsive – 1024×768 minimum – touchscreen friendly
- Accessibility: Font size 4 levels, High-contrast, Dyslexic font, Zoom 100-200%
- Keyboard-navigable CBT
- **Splash Screen** – KLC branded – "Powered by FEMZYK – OLUFEMI BENUA KERIPE"
- **About Page** – full credits – FEMZYK credit here only

**Access**
- JavaFX Desktop – Windows 7/8/10/11 – **x86 + x64 native .exe – Java bundled – Zero Java/JDK/IDE required**
- Web Admin Portal – Netlify Free – Teachers upload questions from any phone/PC browser – Question Upload + Results + Parent Result Checker
- Student exams: JavaFX only – proctoring lockdown
- Portable USB version included

**Tech Stack**
Client: Java 17 LTS + JavaFX 17, Hibernate 6, H2 cache
Cloud: Supabase PostgreSQL 15 Free – Auth + Storage + PostgREST + Realtime
Web: Vanilla JS + supabase-js – Netlify Free
PDF: iText PDF 8
Parsers: Apache PDFBox 3, Apache POI 5
QR: ZXing 3.5
Email: Jakarta Mail 2.0
Webcam: webcam-capture 0.3.12 – optional, graceful fallback
CSV: OpenCSV 5.9
Auth: BCrypt + TOTP (dev.samstevens.totp 1.7.1)
Build: Maven 3.9, jlink + jpackage

**Database – 24 tables**
users, student_profiles, school_classes, subjects, teacher_subjects,
questions, question_options, question_attachments,
exams, exam_questions, exam_attempts, attempt_answers,
ca_scores, results, result_pins, result_appeals,
school_profile, announcements, announcement_reads,
fees_ledger, notification_queue, audit_logs, backup_logs,
sync_queue, id_cards, study_materials, formula_sheets

**Installers**
- `build_windows_x64.bat` → `KnowledgeLandCBT-6.3.0.exe`
- `build_windows_x86.bat` → `KnowledgeLandCBT-x86-6.3.0.exe`
- Java Runtime bundled – 65 MB
- Start Menu shortcut – KLC icon
- Portable USB version

---

© 2025 KNOWLEDGE LAND COLLEGE – Powered by FEMZYK – Lead Developer: OLUFEMI BENUA KERIPE

Result slips are KLC Official – **NO FEMZYK watermark** – FEMZYK credit: Splash Screen / About Page only
#   k l c - e x a m - p o r t a l  
 