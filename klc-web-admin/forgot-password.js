// KLC Forgot Password — resets password via security question.
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));

function msg(el, t, bad) { el.textContent = t || ""; el.className = bad ? "error" : "muted"; el.hidden = !t; }

async function rpc(fn, args) {
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
    let m = `HTTP ${resp.status}`;
    try { m = (await resp.json()).message || m; } catch (_) {}
    throw new Error(m);
  }
  return resp.json();
}

window.addEventListener("DOMContentLoaded", () => {
  const err = $("resetError");
  const ok = $("resetSuccess");

  $("formReset").onsubmit = async (e) => {
    e.preventDefault();
    msg(err, "", false); msg(ok, "", false);
    const email = $("reset_email").value.trim();
    const answer = $("reset_answer").value.trim();
    const np = $("reset_newpass").value;
    const cp = $("reset_confirmpass").value;
    if (np !== cp) { msg(err, "New passwords do not match.", true); return; }
    if (np.length < 8) { msg(err, "Password must be at least 8 characters.", true); return; }
    try {
      await rpc("web_forgot_password", {
        p_email: email,
        p_security_answer: answer,
        p_new_password: np,
      });
      msg(ok, `✅ Password reset for <b>${esc(email)}</b>. You can now <a href="index.html">log in</a> with your new password.`, false);
      $("formReset").reset();
    } catch (ex) { msg(err, ex.message || "Reset failed.", true); }
  };
});