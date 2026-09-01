// KLC CBT Suite v1.0 - Web Admin / Parent Checker configuration
// Replace with YOUR project values (Supabase Dashboard -> Settings -> API).
// The anon key is safe to expose: data is only reachable through the
// SECURITY DEFINER RPCs in supabase/klc_supabase_v1_1_security_and_features.sql.
const KLC_SUPABASE_URL = "https://YOUR_PROJECT_REF.supabase.co";
const KLC_ANON_KEY = "YOUR_SUPABASE_ANON_KEY";

async function klcRpc(fn, args) {
  const resp = await fetch(`${KLC_SUPABASE_URL}/rest/v1/rpc/${fn}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      apikey: KLC_ANON_KEY,
      Authorization: `Bearer ${KLC_ANON_KEY}`,
    },
    body: JSON.stringify(args),
  });
  if (!resp.ok) {
    let msg = `HTTP ${resp.status}`;
    try { msg = (await resp.json()).message || msg; } catch (_) {}
    throw new Error(msg);
  }
  return resp.json();
}
