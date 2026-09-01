# SECURITY_CREDENTIALS — Full Inventory & Rotation Runbook

**Purpose:** every credential that was published in this repository, where it
lives, how to rotate it, and how to wire secrets locally + into GitHub.
**Repo history note:** `config.properties` (Supabase keys, DB password, SMTP
credentials) was **never committed** — those never leaked. The items below
**were** published in README/SQL and MUST be treated as compromised.

---

## 1. PUBLISHED (COMPROMISED) CREDENTIALS — INVENTORY

| # | Credential | Old published value | Was published in | Used by |
|---|---|---|---|---|
| 1 | Super Admin email | `superadmin@knowledgeland.edu.ng` | README, supabase schema SQL | login identity |
| 2 | Super Admin password | `Femi2022-` | README, `klc_supabase_schema.sql`, `klc_supabase_schema_v6_3_final.sql` | Supabase `users` table (BCrypt) |
| 3 | Super Admin BCrypt hash | `$2a$12$HAzNUHHOpylPi702s4pAiOwXxxbCOeNQ2wR22pP2Op/.OPrtAxgwG` | both SQL files | offline verification of #2 |
| 4 | Super Admin registration code | `FEMZYK ENTERPRISES LTD` | README | `AuthService` (default) |
| 5 | Staff registration code | `FEMZYK` | README | `AuthService` (default) |
| 6 | Student registration code | `FEMZYKENTLTD` | README | `AuthService` (default) |
| 7 | Result PIN format | `SURNAME+CLASS` | README | derivable for every student |
| 8 | Supabase project ref | `aqircycpctadgvbqsadf` | `klc_supabase_schema_v6_3_final.sql` header | endpoint `aqircycpctadgvbqsadf.supabase.co` |
| 9 | Teacher bulk-import default password | `Teacher123` | `TeacherBulkImportController` status text | new teacher accounts |
| 10 | Super Admin fixed UUID | `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa` | SQL seeds | predictable target id |
| 11 | **Supabase `anon` key (JWT)** | `eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImFxaXJjeWNwY3RhZGd2YnFzYWRmIi…` (full value in git history) | hardcoded in `ExamManagerController`, `LiveMonitorController`, `ResultsViewController` WebSocket URLs | Supabase REST/Realtime access — **with RLS disabled by `v6_3_final.sql`, the whole cloud DB was effectively public** |

**Never leaked (still rotate on your own schedule):** Supabase anon key,
service_role key, DB pooler password, Brevo SMTP key — all only ever lived in
your local `src/main/resources/config.properties`, which is gitignored.

---

## 2. ROTATE NOW (10 minutes)

### 2a. Super Admin password (items 1–3)
```bash
# generate a strong replacement locally (never type it in chat)
python3 -c "import secrets; print(secrets.token_urlsafe(18))"

# hash it with bcrypt cost 12, then in the Supabase SQL editor run:
#   UPDATE users SET password_hash = '<bcrypt hash>'
#     WHERE email = 'superadmin@knowledgeland.edu.ng';
# (any bcrypt-12 generator works, e.g. https://bcrypt-generator.com —
#  run it locally, not on random websites, if you prefer)
```
Also change the email if you want the login less guessable.

### 2b. Registration codes (items 4–6)
The app reads overrides from `config.properties` — **no recompile needed**:
```properties
code.super_admin=YOUR-NEW-LONG-SUPER-ADMIN-CODE
code.admin=YOUR-NEW-STAFF-CODE
code.student=YOUR-NEW-FAMILY-CODE
```
Put the same file on every lab PC (or just in the lab PC's packaged
`config.properties`). Old code values stop working the moment the file is in
place, because `AuthService` prefers config over compiled defaults.
**Recommendation per school customer:** unique codes per school, not global.

### 2c. Bulk-import default password (item 9)
Change in `TeacherBulkImportController` before next customer deploy (todo in
report) and force `must_change_password=TRUE` on imported teachers in
Supabase:
```sql
UPDATE users SET must_change_password = TRUE WHERE role <> 'STUDENT';
```

### 2d. Supabase project ref + anon key (items 8, 11) — **DO THIS FIRST**
The anon key was published while RLS was disabled, so treat the cloud data
as exposed.
1. Supabase Dashboard → Settings → API → **Rotate/regenerate the `anon` key**
   (and rotate the `service_role` key for good measure).
2. Put the new `supabase.key` into every lab PC's `config.properties`.
3. Run `supabase/klc_supabase_v1_1_security_and_features.sql` — it re-enables
   RLS with service-role policies so a future key leak can never read the
   school data again.
4. Project ref: if you want it unlisted entirely, create a new Supabase
   project and migrate (pg_dump/restore), then update `config.properties`
   on lab PCs.

---

## 3. STORE AS SECRETS

### GitHub repo secrets (Settings → Secrets and variables → Actions, or CLI)
The CI build itself needs **no** secrets (plain `mvn package`). Set these so
future release/packaging workflows can inject a config automatically:
```bash
gh secret set KLC_SUPABASE_URL        --body "https://YOUR_PROJECT.supabase.co"
gh secret set KLC_SUPABASE_ANON_KEY   --body "<anon key from local config.properties>"
gh secret set KLC_SUPABASE_DB_POOLER  --body "jdbc:postgresql://YOUR_POOLER_HOST:6543/postgres"
gh secret set KLC_SUPABASE_DB_USER    --body "postgres.YOUR_PROJECT_REF"
gh secret set KLC_SUPABASE_DB_PASS    --body "<pooler password from local config.properties>"
gh secret set KLC_SMTP_HOST           --body "smtp-relay.brevo.com"
gh secret set KLC_SMTP_USER           --body "<brevo login>"
gh secret set KLC_SMTP_PASS           --body "<brevo smtp key>"
gh secret set KLC_SMTP_FROM_EMAIL     --body "<verified sender>"
gh secret set KLC_CODE_SUPER_ADMIN    --body "<new super admin code>"
gh secret set KLC_CODE_ADMIN          --body "<new staff code>"
gh secret set KLC_CODE_STUDENT        --body "<new student/family code>"
```

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

### One-command local generator
Run **on your machine** (never paste output into chat):
```powershell
# Windows PowerShell
powershell -ExecutionPolicy Bypass -File tools\generate_secrets.ps1
```
```bash
# macOS / Linux
bash tools/generate_secrets.sh
```
They create/refresh `config.properties`, print `gh secret set ...` lines, and
generate the Supabase `UPDATE users ...` statement with a fresh BCrypt-12
hash.

---

## 4. PROFESSIONAL SWEEP CHECKLIST

- [x] Password, hash, codes, project ref removed from repo working tree
      (README + both SQL files scrubbed in this release)
- [ ] **History still contains them** — rotation above is what actually
      protects you. Optional extra: `git filter-repo --replace-text` and
      force-push (coordinate with all clones), or accept + rotate + document.
- [ ] Rotate super admin password in Supabase
- [ ] Set new registration codes in every lab PC `config.properties`
- [ ] Force `must_change_password` for all staff
- [ ] Add the `gh secret set` values (section 3)
- [ ] Enable Supabase Auth rate limits + MFA on the dashboard account
- [ ] Run `supabase/klc_supabase_v1_1_security_and_features.sql`
      (WORM audit, RLS, storage bucket)
- [ ] Verify: old password rejected, old codes rejected on registration
