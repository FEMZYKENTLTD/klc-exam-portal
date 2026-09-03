# SECURITY_CREDENTIALS — Full Inventory & Rotation Runbook

**Purpose:** every credential that was ever published in this repository,
where it lived, how to rotate it, and how to wire secrets locally + into
GitHub. **This file intentionally re-publishes NO values** — the inventory
below names each item and where it leaked, so you know exactly what to
rotate; the actual values are redacted (they are still in git *history*,
which is why rotation is mandatory).

**Repo history note:** `config.properties` (Supabase keys, DB password, SMTP
credentials) was **never committed** — those never leaked. The items below
**were** published in the README/SQL files and MUST be treated as
compromised until rotated.

---

## 1. PUBLISHED (COMPROMISED) CREDENTIALS — INVENTORY

| # | Credential | Old value | Was published in | Used by |
|---|---|---|---|---|
| 1 | Super Admin email | `superadmin@knowledgeland.edu.ng` | README, supabase schema SQL | login identity |
| 2 | Super Admin password | **REDACTED** (rotated 2026-09) | README, `klc_supabase_schema.sql`, `klc_supabase_schema_v6_3_final.sql` | Supabase `users` table (BCrypt) |
| 3 | Super Admin BCrypt hash | **REDACTED** (bcrypt-12) | both SQL files | offline verification of #2 |
| 4 | Super Admin registration code | **REDACTED** (company-name-based) | README | `AuthService` (compiled default) |
| 5 | Staff registration code | **REDACTED** (company abbreviation) | README | `AuthService` (compiled default) |
| 6 | Student registration code | **REDACTED** (company name) | README | `AuthService` (compiled default) |
| 7 | Result PIN format | `SURNAME+CLASS` | README | derivable for every student — this is BY DESIGN (parents use it with the admission number); keep per-term regeneration |
| 8 | Supabase project ref | **REDACTED** (endpoint = `<ref>.supabase.co`) | `klc_supabase_schema_v6_3_final.sql` header | project URL |
| 9 | Teacher bulk-import default password | **REDACTED** (fixed weak default) | `TeacherBulkImportController` status text | new teacher accounts — **fixed in code 2026-09**: now config-driven (`import.default_password`) or a random one-time password shown once after import |
| 10 | Super Admin fixed UUID | **REDACTED** (predictable all-`a` seed) | SQL seeds | predictable target id |
| 11 | **Supabase `anon` key (JWT)** | **REDACTED** (full value in git history) | hardcoded in `ExamManagerController`, `LiveMonitorController`, `ResultsViewController` WebSocket URLs | Supabase REST/Realtime access — **with RLS disabled by `v6_3_final.sql`, the whole cloud DB was effectively public** |

**Never leaked (still rotate on your own schedule):** Supabase anon key,
service_role key, DB pooler password, Brevo SMTP key — all only ever lived
in your local `src/main/resources/config.properties`, which is gitignored.

---

## 2. ROTATE NOW (10 minutes)

### 2a. Super Admin password (items 1–3)
```bash
# generate a strong replacement locally (never type it in chat)
python3 -c "import secrets; print(secrets.token_urlsafe(18))"

# hash it with bcrypt cost 12, then in the Supabase SQL editor run:
#   UPDATE users SET password_hash = '<bcrypt hash>'
#     WHERE email = 'superadmin@knowledgeland.edu.ng';
# (any bcrypt-12 generator works; run it locally if you prefer)
```
Also change the email if you want the login less guessable.

### 2b. Registration codes (items 4–6)
The app reads overrides from `config.properties` — **no rebuild needed**:
```properties
code.super_admin=YOUR-NEW-LONG-SUPER-ADMIN-CODE
code.admin=YOUR-NEW-STAFF-CODE
code.student=YOUR-NEW-FAMILY-CODE
```
Put the same file on every lab PC (or just in the lab PC's packaged
`config.properties`). Old code values stop working the moment the file is
in place, because `AuthService` prefers config over compiled defaults.
**Recommendation per school customer:** unique codes per school, not global.
The admin screen (**School Settings → Rotate codes**) now prints the codes
actually in effect, so you can verify which set is live.

### 2c. Bulk-import default password (item 9)
**Fixed in code (2026-09-02):** `TeacherBulkImportController` no longer
ships a fixed default. It now uses `import.default_password` from
`config.properties` when set, otherwise generates a strong random password
and shows it **once** in the import status line. Teacher Manager's
"Reset password" likewise issues a one-time random password instead of a
fixed value. Action: tell any teacher already created with the old default
to log in and change it (or reset them from Teacher Manager — the new
one-time password is shown there).

### 2d. Supabase project ref + anon key (items 8, 11) — **DO THIS FIRST**
The anon key was published while RLS was disabled, so treat the cloud data
as exposed.
1. Supabase Dashboard → Settings → API → **Rotate/regenerate the `anon` key**
   (and rotate the `service_role` key for good measure).
2. Put the new `supabase.key` into every lab PC's `config.properties`.
3. Run `supabase/klc_supabase_v1_1_security_and_features.sql` — it re-enables
   RLS with service-role policies so a future key leak can never read the
   school data again. (CI runs it automatically on every push to `main`.)
4. Project ref: if you want it unlisted entirely, create a new Supabase
   project and migrate (pg_dump/restore), then update `config.properties`
   on lab PCs.

---

## 3. STORE AS SECRETS

### GitHub repo secrets — the exact names `.github/workflows/build.yml` consumes

| Secret | Value source (your local `config.properties`) |
|---|---|
| `KLC_SUPABASE_URL` | `supabase.url` |
| `KLC_SUPABASE_ANON_KEY` | `supabase.key` |
| `KLC_DB_HOST` | host from `supabase.db.pooler.url` |
| `KLC_DB_PORT` | port from `supabase.db.pooler.url` (usually `6543`) |
| `KLC_DB_USER` | `supabase.db.pooler.user` |
| `KLC_DB_PASS` | `supabase.db.pooler.password` |
| `KLC_CODE_SUPER_ADMIN` | `code.super_admin` |
| `KLC_CODE_ADMIN` | `code.admin` |
| `KLC_CODE_STUDENT` | `code.student` |
| `KLC_SMTP_HOST` | `smtp.host` |
| `KLC_SMTP_PORT` | `smtp.port` |
| `KLC_SMTP_USER` | `smtp.user` |
| `KLC_SMTP_PASS` | `smtp.pass` |
| `KLC_SMTP_FROM_NAME` | `smtp.from.name` |
| `KLC_SMTP_FROM_EMAIL` | `smtp.from.email` |

**Optional shortcut:** instead of `KLC_DB_HOST` + `KLC_DB_PORT`, a single
`KLC_DB_POOLER_URL` set to your full `supabase.db.pooler.url` value works —
CI parses the host/port from it (individual secrets take priority when
both are present).

> Note on names: GitHub secrets are case-sensitive and there is no rename
> in the UI — a mistyped name (e.g. `SMTP_FROM_EMAIL` instead of
> `KLC_SMTP_FROM_EMAIL`) is simply invisible to the workflow. Copy the
> names above exactly.

**One-command setup (reads your local config, prints nothing):**
```powershell
# Windows PowerShell 5.1 compatible; uses the gh CLI if logged in,
# otherwise asks for a GitHub token (classic 'repo' scope or fine-grained
# 'Actions: write'). No values are ever shown on screen.
powershell -ExecutionPolicy Bypass -File tools\setup_github_secrets.template.ps1
# preview without pushing:
powershell -ExecutionPolicy Bypass -File tools\setup_github_secrets.template.ps1 -DryRun
```
Manual alternative: GitHub → Settings → Secrets and variables → Actions →
"New repository secret", enter each of the 15 names above.

### Local `src/main/resources/config.properties` (the file the app actually reads)
Copy `config.properties.example` → `config.properties` and fill every value.
It is gitignored, so it stays on the machine:
```properties
supabase.url=https://YOUR_PROJECT.supabase.co
supabase.key=YOUR_SUPABASE_ANON_OR_SERVICE_KEY
supabase.storage.bucket=klc-attachments
supabase.db.url=jdbc:postgresql://db.YOUR_PROJECT.supabase.co:5432/postgres
supabase.db.user=postgres
supabase.db.password=YOUR_DB_PASSWORD
supabase.db.pooler.url=jdbc:postgresql://YOUR_POOLER_HOST:6543/postgres
supabase.db.pooler.user=postgres.YOUR_PROJECT_REF
supabase.db.pooler.password=YOUR_DB_PASSWORD
code.super_admin=YOUR-NEW-LONG-SUPER-ADMIN-CODE
code.admin=YOUR-NEW-STAFF-CODE
code.student=YOUR-NEW-FAMILY-CODE
social.allow_student_staff_dm=false
social.allow_student_attachments=false
smtp.host=smtp-relay.brevo.com
smtp.port=587
smtp.user=YOUR_BREVO_SMTP_LOGIN
smtp.pass=YOUR_BREVO_SMTP_KEY
smtp.from.name=KNOWLEDGE LAND COLLEGE CBT
smtp.from.email=YOUR_SENDER_EMAIL
```

### One-command local config generator
Run **on your machine** (never paste output into chat):
```powershell
# Windows PowerShell
powershell -ExecutionPolicy Bypass -File tools\generate_secrets.ps1
```
```bash
# macOS / Linux
bash tools/generate_secrets.sh
```
They create/refresh `config.properties` with fresh random registration
codes, print the new codes once, and print matching `gh secret set` lines.

---

## 4. PROFESSIONAL SWEEP CHECKLIST

- [x] Password, hash, codes, project ref removed from repo working tree
      (README + both SQL files scrubbed in the v1.0 release)
- [x] `SECURITY_CREDENTIALS.md` itself no longer re-publishes the values
      (redacted 2026-09-02)
- [ ] **History still contains the old values** — rotation above is what
      actually protects you. Optional extra: `git filter-repo
      --replace-text` and force-push (coordinate with all clones), or
      accept + rotate + document.
- [ ] Rotate super admin password in Supabase
- [ ] Set new registration codes in every lab PC `config.properties`
- [ ] Reset staff accounts created with the old bulk-import default
      (Teacher Manager → Reset password now shows a one-time password)
- [ ] Set the 15 GitHub repo secrets (section 3 — template script)
- [ ] Enable Supabase Auth rate limits + MFA on the dashboard account
- [x] `supabase/klc_supabase_v1_1_security_and_features.sql` shipped
      (WORM audit trigger, RLS, storage bucket, **PARENT role fix**,
      web-portal RPCs) — CI runs it on every push to main
- [ ] Verify: old password rejected, old codes rejected on registration
