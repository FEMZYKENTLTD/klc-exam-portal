// KLC Registration — public signup for student, teacher/staff, and parent accounts.
// Calls web_register_student / web_register_teacher / web_register_parent RPCs
// (SECURITY DEFINER, bcrypt, registration-code validated).
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const CLASSES = ["JSS1","JSS2","JSS3","SS1","SS2","SS3"];

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
  const err = $("regError");
  const acctype = () => document.querySelector('input[name="acctype"]:checked').value;

  // Show/hide form sections based on account type
  const refreshForms = () => {
    const t = acctype();
    $("formStudent").hidden = t !== "STUDENT";
    $("formTeacher").hidden = t !== "TEACHER";
    $("formParent").hidden = t !== "PARENT";
    $("formStudent").style.display = t === "STUDENT" ? "" : "none";
    $("formTeacher").style.display = t === "TEACHER" ? "" : "none";
    $("formParent").style.display = t === "PARENT" ? "" : "none";
  };
  document.querySelectorAll('input[name="acctype"]').forEach(r => r.onchange = refreshForms);
  refreshForms();

  async function submitStudent(e) {
    e.preventDefault(); msg(err, "", false);
    try {
      const out = await rpc("web_register_student", {
        p_email: $("reg_student_email").value.trim(),
        p_password: $("reg_student_pass").value,
        p_code: $("reg_student_code").value.trim(),
        p_full_name: $("reg_student_name").value.trim(),
        p_admission_no: $("reg_student_admno").value.trim(),
        p_class_level: $("reg_student_class").value,
        p_arm: $("reg_student_arm").value.trim() || null,
        p_gender: $("reg_student_gender").value || null,
        p_parent_phone: $("reg_student_parent_phone").value.trim() || null,
        p_parent_email: $("reg_student_parent_email").value.trim() || null,
        p_session: $("reg_student_session").value.trim() || null,
        p_date_of_birth: $("reg_student_dob").value || null,
        p_address: $("reg_student_address").value.trim() || null,
      });
      msg(err, `✅ Account created! Result PIN: <b>${esc(out.result_pin)}</b> · Admission No: <b>${esc(out.admission_no)}</b><br/>Save these — you need them to log in and for the Parent Checker. You can now <a href="student.html">log in as a student</a>.`, false);
      $("formStudent").reset();
    } catch (ex) { msg(err, ex.message || "Registration failed.", true); }
  }

  async function submitTeacher(e) {
    e.preventDefault(); msg(err, "", false);
    try {
      const subjects = $("reg_teacher_subjects").value.split(",").map(s => s.trim()).filter(Boolean);
      const out = await rpc("web_register_teacher", {
        p_email: $("reg_teacher_email").value.trim(),
        p_password: $("reg_teacher_pass").value,
        p_code: $("reg_teacher_code").value.trim(),
        p_full_name: $("reg_teacher_name").value.trim(),
        p_role: $("reg_teacher_role").value,
        p_phone: $("reg_teacher_phone").value.trim() || null,
        p_subject_codes: subjects,
      });
      msg(err, `✅ Staff account created! Role: <b>${esc(out.role)}</b>. You can now <a href="index.html">log in to the Staff Console</a>.`, false);
      $("formTeacher").reset();
    } catch (ex) { msg(err, ex.message || "Registration failed.", true); }
  }

  async function submitParent(e) {
    e.preventDefault(); msg(err, "", false);
    try {
      const out = await rpc("web_register_parent", {
        p_email: $("reg_parent_email").value.trim(),
        p_password: $("reg_parent_pass").value,
        p_code: $("reg_parent_code").value.trim(),
        p_full_name: $("reg_parent_name").value.trim(),
        p_ward_admission_no: $("reg_parent_ward").value.trim(),
        p_relationship: $("reg_parent_rel").value.trim() || null,
        p_phone: $("reg_parent_phone").value.trim() || null,
      });
      msg(err, `✅ Parent account created! Linked to ward <b>${esc(out.ward_admission_no)}</b>. You can now use the <a href="parent.html">Parent Result Checker</a> with the ward's admission number and result PIN.`, false);
      $("formParent").reset();
    } catch (ex) { msg(err, ex.message || "Registration failed.", true); }
  }

  $("formStudent").onsubmit = submitStudent;
  $("formTeacher").onsubmit = submitTeacher;
  $("formParent").onsubmit = submitParent;
});