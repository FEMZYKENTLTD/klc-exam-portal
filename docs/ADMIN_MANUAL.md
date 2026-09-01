# KLC CBT Suite v1.0 — Admin Manual
KNOWLEDGE LAND COLLEGE CBT Suite · Powered by FEMZYK ENTERPRISES LTD

## 1. First boot
1. Install the app (see INSTALLER_GUIDE.md) and configure `config.properties`
   next to the JAR (Supabase URL/key, DB pooler, SMTP, registration codes).
2. Start the app — the super-admin account is seeded automatically. The
   one-time password prints to the console (or set `app.superadmin.password`
   before first boot). **Change it immediately.**
3. Register staff with the staff code, students/parents with the family code
   (codes live in `config.properties` — rotate per school).

## 2. Daily admin flow
- **School Settings** — name, logo, motto, principal signature, campus,
  session/term. Appears on every official document.
- **Students** — CSV bulk import (500+), auto admission numbers
  (`KLC/{CLASS}/{#}`), auto Result PINs (`SURNAME+CLASS`, collisions get the
  last-3 admission digits appended, e.g. `KERIPESS2045`), regenerate PINs,
  reset passwords, fee status.
- **Subject Manager** — CRUD + codes; subjects with existing exams can never
  be deleted (deactivate instead).
- **Exam Manager** — create exams (manual pick or auto-pick random pool by
  topic/difficulty), variants A–D, negative marking, schedules, fee gate,
  clone to next term/session, live monitor with extend-time / force-submit.

## 3. Results & reporting
- Results → instant slips (QR verified), term report cards, broadsheets
  (Excel/PDF), cumulative transcripts, graduation certificates.
- Analytics → class averages, pass rates, topic-by-topic breakdown,
  item analysis (difficulty, discrimination index, distractor analysis),
  teacher workload. **Export Report (CSV)** button included.
- Parents check results themselves at the web portal (klc-web-admin) with
  Admission No + Result PIN.

## 4. Users, safety & audit
- **Create User** (Super Admin) — create staff, promote/demote, activate,
  reset password (also clears lockouts), **unlock malpractice bans**.
- **2FA Security** — optional TOTP for any staff account (Google
  Authenticator/Authy; scan QR, enter code).
- **Audit Logs** — immutable (WORM trigger in the cloud DB); every login,
  strike, upload, result change; exportable from the screen.

## 5. Backup & recovery
- **Backup** — 1-click `.klcbackup` (all tables CSV + manifest + SHA-256).
  Set `backup.key` in config → file is AES-256-GCM encrypted (header
  `KLCENC1`). Auto-backup toggle runs every 24h while the Backup screen's
  app is open. Cloud point-in-time restore: Supabase Pro.
- Restore = unzip (or decrypt first), then re-import CSVs / pg-COPY; the
  cloud DB itself is the primary safety net.
