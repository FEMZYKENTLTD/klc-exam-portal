# KLC CBT Suite — API Access (ENTERPRISE tier)

All school data lives in **Supabase (PostgreSQL)**, which exposes a
production-grade **REST API (PostgREST)** over your tables out of the box.
Enterprise customers get read API access without any additional server.

## Base URL
```
https://<YOUR_PROJECT_REF>.supabase.co/rest/v1
```

## Authentication
Two keys (Supabase Dashboard → Settings → API):

| Key | Purpose |
|---|---|
| `anon` | public browser role — constrained by **Row Level Security** |
| `service_role` | server-to-server full access — **never expose to browsers** |

All requests need:
```
apikey: <KEY>
Authorization: Bearer <KEY>
```

> RLS: run `supabase/klc_supabase_v1_1_security_and_features.sql` so every
> sensitive table has RLS enabled with a `service_role` policy — the desktop
> app is unaffected (it connects via the Postgres role), while anon REST
> access sees nothing unless you deliberately add per-table public policies.

## Examples

**Read results for one student (service key, server-side):**
```bash
curl "https://$PROJECT.supabase.co/rest/v1/results?student_id=eq.$STUDENT_UUID&select=score,percentage,created_at" \
  -H "apikey: $SERVICE_KEY" -H "Authorization: Bearer $SERVICE_KEY"
```

**Join via views** — create views in the Supabase SQL editor for common
partner queries, e.g.:
```sql
CREATE VIEW api_parent_friendly_results AS
SELECT r.id, sp.admission_no, s.subject_code, r.score,
       r.total_questions, r.percentage, r.created_at
FROM results r
JOIN student_profiles sp ON sp.user_id = r.student_id
JOIN exams e  ON e.id = r.exam_id
JOIN subjects s ON s.id = e.subject_id
WHERE COALESCE(r.published, TRUE);
GRANT SELECT ON api_parent_friendly_results TO anon, service_role;
```
```bash
curl "https://$PROJECT.supabase.co/rest/v1/api_parent_friendly_results?admission_no=eq.KLC%2FSS2%2F045"
```

**Pagination / filtering / ordering** (PostgREST syntax):
```
?limit=50&offset=100&order=created_at.desc&percentage=gte.50
```

## Ground rules for integrators
1. Only `SELECT` access for partners — writes stay in the desktop app.
2. Never ship `service_role` in a browser/mobile app.
3. `audit_logs` is **WORM** (append-only enforced by trigger) — you can
   stream it for SIEM, you cannot edit it.
4. Rate limits / API keys are managed per customer by FEMZYK ENTERPRISES LTD.
