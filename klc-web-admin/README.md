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
  `sessionStorage`. For v1.1, migrate staff to **Supabase Auth** (auth.users)
  and switch RPCs to `auth.uid()`-based checks for token-based sessions.
- Parent PINs are the access secret — keep regenerating them per term
  (Super Admin → Student Manager → Regenerate PIN).

## Optional: supabase-js
The portal uses plain `fetch()` to PostgREST RPCs, so no JS libraries are
required. If you add real-time features later, drop in
[supabase-js](https://supabase.com/docs/reference/javascript) and the same
RPCs work unchanged.
