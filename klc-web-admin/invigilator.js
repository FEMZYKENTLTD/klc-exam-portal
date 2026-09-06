// Live exam room (invigilator mobile monitor). Reuses the staff session
// stored by index.html (klc_email / klc_pass). Every poll re-verifies the
// credentials server-side in staff_live_room (SECURITY DEFINER).
const session = {
  email: sessionStorage.getItem("klc_email") || "",
  password: sessionStorage.getItem("klc_pass") || "",
  role: sessionStorage.getItem("klc_role") || "",
};
const REFRESH_S = 15;
let timer = null;

function esc(s) {
  return String(s ?? "-").replace(/[&<>"']/g,
    c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;",
            "'": "&#39;" }[c]));
}
function minsAgo(iso) {
  if (!iso) return "-";
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 0) return "0m";
  return Math.floor(ms / 60000) + "m";
}

window.addEventListener("DOMContentLoaded", () => {
  document.getElementById("loginForm").addEventListener("submit", async e => {
    e.preventDefault();
    const err = document.getElementById("loginError");
    err.hidden = true;
    session.email = document.getElementById("email").value.trim();
    session.password = document.getElementById("password").value;
    try {
      session.role = await klcRpc("staff_check",
        { p_email: session.email, p_password: session.password });
      sessionStorage.setItem("klc_email", session.email);
      sessionStorage.setItem("klc_pass", session.password);
      sessionStorage.setItem("klc_role", session.role);
      enterRoom();
    } catch (ex) {
      err.textContent = ex.message || "Login failed.";
      err.hidden = false;
    }
  });
  document.getElementById("refreshBtn").onclick = loadRoom;
  document.getElementById("logout").onclick = () => {
    sessionStorage.clear(); location.reload();
  };
  if (session.email && session.password) enterRoom();
});

function enterRoom() {
  document.getElementById("loginCard").hidden = true;
  document.getElementById("room").hidden = false;
  document.getElementById("who").textContent =
    "LIVE ROOM — " + session.email.replace(/@.*/, "") +
    " · " + String(session.role || "").replace("_", " ");
  loadRoom();
  if (timer) clearInterval(timer);
  timer = setInterval(loadRoom, REFRESH_S * 1000);
}

async function loadRoom() {
  try {
    const rows = await klcRpc("staff_live_room",
      { p_email: session.email, p_password: session.password, p_minutes: 240 });
    render(rows || []);
    document.getElementById("nextRefresh").textContent = REFRESH_S + "s";
  } catch (ex) {
    if (String(ex.message).includes("Invalid credentials")) {
      sessionStorage.clear();
      location.reload();
      return;
    }
    document.getElementById("roomMsg").textContent =
      "Refresh error: " + ex.message;
  }
}

function render(rows) {
  const tb = document.getElementById("roomBody");
  document.getElementById("liveCount").textContent = rows.length;
  if (!rows.length) {
    tb.innerHTML = `<tr><td colspan="9">No official exam attempts in
      progress right now.</td></tr>`;
    return;
  }
  tb.innerHTML = rows.map(r => {
    const st = Number(r.strike_count || 0);
    const pill = st >= 3 ? '<span class="pill flag">3+ STRIKES</span>'
      : st > 0 ? '<span class="pill strike">' + st + " strike</span>"
      : '<span class="pill live">0</span>';
    return `<tr>
      <td class="long">${esc(r.student)}</td>
      <td>${esc(r.admission_no)}</td>
      <td class="long">${esc(r.exam_title)} (${esc(r.subject_code)})</td>
      <td>${esc(r.class_level)}</td><td>${esc(r.arm)}</td>
      <td>${esc(r.started_at).slice(11, 16)}</td>
      <td>${minsAgo(r.started_at)}</td>
      <td>${pill}</td>
      <td>${esc(r.status)}</td></tr>`;
  }).join("");
}
