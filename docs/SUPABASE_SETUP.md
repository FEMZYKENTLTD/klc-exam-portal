# Supabase Setup Guide (10 minutes)

1. Create a free project at https://supabase.com (500 MB DB, free forever).
2. SQL Editor → run in order:
   `supabase/klc_supabase_schema.sql`
   → `supabase/klc_supabase_schema_v6_2.sql`
   → `supabase/klc_supabase_schema_v6_3_final.sql`
   → `supabase/klc_supabase_schema_v1_0_social.sql`
   → `supabase/klc_supabase_v1_1_security_and_features.sql`
   (Or just let CI migrate automatically — see `docs/github-actions-*.install-me`.)
3. Settings → API → copy **URL**, **anon key**, **service key**.
4. Settings → Database → connection pooling → copy the **pooler** host
   (port 6543) + reset the DB password.
5. Fill `config.properties` (template: `config.properties.example`):
   supabase.url / supabase.key / supabase.db.pooler.* / smtp.* / codes.
6. **Rotate everything that ever appeared in chat/git** (see
   SECURITY_CREDENTIALS.md).
7. Create the Storage bucket if not auto-created: `klc-attachments`
   (public read) — the v1.1 SQL does this.

Notes: the desktop app talks to Postgres over JDBC (SSL); RLS is enabled for
all anon/web paths; the desktop DB role bypasses RLS by design. The web
portal only touches SECURITY DEFINER RPCs. "Supabase Auth" for the desktop
role maps to your BCrypt accounts in `public.users` (JDBC has no JWT);
for token-based web sessions adopt supabase-js auth in v1.1.
