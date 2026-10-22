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
