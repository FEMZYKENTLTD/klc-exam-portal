# KLC Web Admin + Parent Result Checker (v1.0)

Static web portal for **KNOWLEDGE LAND COLLEGE CBT Suite** — spec deliverable
13.3. Zero dependencies, zero build step, $0 hosting (Netlify / GitHub Pages).

## Pages
| Page | Who | What |
|---|---|---|
| `parent.html` | **PUBLIC** | Parent Result Checker — Admission No + Result PIN (`SURNAME+CLASS`) → view/print/download published term results |
| `index.html` | Staff | Login (Teacher / Exam Officer / Principal / Super Admin) → recent published results, class broadsheet (CA + Exam merged, CSV export), subject directory |

## Setup (2 minutes)
1. Run `supabase/klc_supabase_v1_1_security_and_features.sql` in the Supabase
   SQL editor — it creates the RPC functions the portal calls
   (`parent_lookup_results`, `staff_check`, `staff_recent_results`,
   `staff_broadsheet`, `staff_subjects`).
2. Edit `config.js` — put your project URL + anon key.
3. Deploy:
   ```bash
   npx netlify-cli deploy --dir . --prod      # Netlify
   # or push to a repo and enable GitHub Pages on this folder
   ```

## Security model (read this)
- The **anon key is public** by design. It can NOT read any table directly —
  RLS is enabled on all sensitive tables, and data is only reachable through
  `SECURITY DEFINER` RPC functions that:
  - verify the ward's Admission No + Result PIN for parents, or
  - verify staff email + **bcrypt password** against `public.users` on every
    call (`staff_check`) and refuse non-staff roles.
- v1 tradeoff (honest): staff credentials are re-sent per call from
  `sessionStorage`. For a future release, migrate staff to **Supabase Auth** (auth.users)
  and switch RPCs to `auth.uid()`-based checks for token-based sessions.
- Parent PINs are the access secret — keep regenerating them per term
  (Super Admin → Student Manager → Regenerate PIN).

## Optional: supabase-js
The portal uses plain `fetch()` to PostgREST RPCs, so no JS libraries are
required. If you add real-time features later, drop in
[supabase-js](https://supabase.com/docs/reference/javascript) and the same
RPCs work unchanged.

---

## Advanced Web Console (v1.4) — deploy on Vercel

The portal now mirrors the desktop app for everything except **sitting exams**
(exam attempts can only start/finish in the proctored desktop app — by design,
there is not even a web API for it).

| Page | Who | What |
|---|---|---|
| `index.html` | Staff | Full console: dashboard, students, teachers & subjects assignment, classes, subjects, question bank + editor + moderation, exam manager + question attach, CA scores (grid/CSV import-export), results + publish/unpublish, broadsheet, analytics (+JSON export), appeals triage, announcements, study materials, live exam room, notification queue, WORM audit trail, school settings + grading scale |
| `student.html` | Students | Own results (with verify codes), CA scores, study materials, announcements, appeals (submit + track) — own rows only, enforced in the database |
| `parent.html` | Parents | Full ward report (results + CA) with result PIN, printable |
| `invigilator.html` | Staff | Live exam room (auto-refresh) |
| `question_upload.html` | Staff | Structured bulk question upload |

Backend: `supabase/klc_supabase_v1_4_web_portal.sql` — role-guarded
`SECURITY DEFINER` RPCs only (same security model as above; no table policies
were opened). Already applied to the production project.

### Deploy to Vercel (2 minutes)
1. Vercel → **Add New → Project** → import `FEMZYKENTLTD/klc-exam-portal`.
2. **Root Directory:** `klc-web-admin` (Framework preset: *Other* — it's static).
3. Deploy. `vercel.json` in this folder provides clean URLs
   (`/console`, `/student`, `/parent`, `/live`, `/upload`), security headers
   (CSP pinned to this Supabase project, frame/noscript protections) and caching.
4. (Optional) add your custom domain in Vercel → Settings → Domains.

No environment variables are needed: `config.js` contains only the public
publishable key, which is safe to expose because tables stay RLS-locked and
every RPC re-verifies credentials + role server-side on each call.


## Web completion (v1.5) — every desktop feature, exams gated to the app

- **Exams are visible on the web but never sat on the web.** The student portal
  lists every exam scheduled for the student's class (mode, window, duration,
  attempts left). Clicking one opens `exam-gate.js` — a dialog explaining why
  exams live in the proctored desktop app (kiosk lockdown, 3-strike focus
  detection, offline autosave), how to download/install it, and how results
  flow back to this portal. Staff get the same preview from Exam Manager.
  There is no start-exam or submit-answer web API at all.
- **Student portal additions:** printable term Report Card, printable Student
  ID Card, Friends & Messages (directory, requests, threads; students message
  accepted friends or staff), profile editing, password change, and 2FA
  (TOTP) setup/enable/disable — TOTP verified server-side against RFC 6238.
- **Staff console additions:** student CSV bulk import (with per-row PIN
  report), exam clone, attached-question viewer with detach, question
  version-history viewer, "My account" password/2FA, and a one-click full
  JSON data export (students/teachers/subjects/questions/exams/results/audit).
- Parent report is now school-branded for printing.

Backend: `supabase/klc_supabase_v1_5_web_complete.sql` (applied live). Honest
boundaries: desktop-only by nature — the encrypted offline `.klcbackup` file
(web equivalent: full JSON export above), OS kiosk policy pack, and webcam/
hardware proctoring. 2FA is enrolled/managed on the web and enforced at
desktop-app login.
