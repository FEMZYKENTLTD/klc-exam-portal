#!/usr/bin/env bash
# KLC CBT Suite v1.0 - local secret generator (macOS/Linux)
# Run:  bash tools/generate_secrets.sh
# Writes config.properties with NEW random registration codes and prints
# the matching `gh secret set` commands. NEVER commit or paste the output.
set -euo pipefail

rand_code() { LC_ALL=C tr -dc 'A-HJ-NP-Z2-9' < /dev/urandom | head -c "${1:-24}"; echo; }
rand_pass() { LC_ALL=C tr -dc 'A-HJ-NP-Za-km-z2-9!@#%&*-_' < /dev/urandom | head -c 20; echo; }

CODE_SUPER_ADMIN=$(rand_code 28)
CODE_ADMIN=$(rand_code 16)
CODE_STUDENT=$(rand_code 16)
ADMIN_PASSWORD=$(rand_pass)

CFG="src/main/resources/config.properties"
if [ -f "$CFG" ]; then cp "$CFG" "$CFG.bak"; echo "Backed up existing config.properties -> config.properties.bak"; fi

SUPA_URL=$(grep -E '^supabase.url=' "$CFG" 2>/dev/null | cut -d= -f2- || true)
[ -z "${SUPA_URL:-}" ] && SUPA_URL="https://YOUR_PROJECT.supabase.co"

mkdir -p "$(dirname "$CFG")"
cat > "$CFG" <<EOF
supabase.url=$SUPA_URL
supabase.key=$(grep -E '^supabase.key=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'YOUR_SUPABASE_ANON_OR_SERVICE_KEY')
supabase.storage.bucket=klc-attachments
supabase.db.url=$(grep -E '^supabase.db.url=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'jdbc:postgresql://db.YOUR_PROJECT.supabase.co:5432/postgres')
supabase.db.user=$(grep -E '^supabase.db.user=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'postgres')
supabase.db.password=$(grep -E '^supabase.db.password=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'YOUR_DB_PASSWORD')
supabase.db.pooler.url=$(grep -E '^supabase.db.pooler.url=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'jdbc:postgresql://YOUR_POOLER_HOST:6543/postgres')
supabase.db.pooler.user=$(grep -E '^supabase.db.pooler.user=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'postgres.YOUR_PROJECT_REF')
supabase.db.pooler.password=$(grep -E '^supabase.db.pooler.password=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'YOUR_DB_PASSWORD')
code.super_admin=$CODE_SUPER_ADMIN
code.admin=$CODE_ADMIN
code.student=$CODE_STUDENT
social.allow_student_staff_dm=false
social.allow_student_attachments=false
smtp.host=$(grep -E '^smtp.host=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'smtp-relay.brevo.com')
smtp.port=$(grep -E '^smtp.port=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo '587')
smtp.user=$(grep -E '^smtp.user=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'YOUR_BREVO_SMTP_LOGIN')
smtp.pass=$(grep -E '^smtp.pass=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'YOUR_BREVO_SMTP_KEY')
smtp.from.name=KNOWLEDGE LAND COLLEGE CBT
smtp.from.email=$(grep -E '^smtp.from.email=' "$CFG.bak" 2>/dev/null | cut -d= -f2- || echo 'YOUR_SENDER_EMAIL')
EOF

echo "✅ Wrote $CFG (gitignored - safe). Keep a copy in your password manager."
echo
echo "═══ NEW REGISTRATION CODES ═══"
echo "code.super_admin = $CODE_SUPER_ADMIN"
echo "code.admin       = $CODE_ADMIN"
echo "code.student     = $CODE_STUDENT"
echo
echo "═══ NEW SUPER ADMIN PASSWORD ═══"
echo "password = $ADMIN_PASSWORD"
echo
echo "═══ gh secret set commands ═══"
echo "gh secret set KLC_CODE_SUPER_ADMIN --body \"$CODE_SUPER_ADMIN\""
echo "gh secret set KLC_CODE_ADMIN       --body \"$CODE_ADMIN\""
echo "gh secret set KLC_CODE_STUDENT     --body \"$CODE_STUDENT\""
echo
echo "SQL to rotate the super admin password:"
echo "  UPDATE users SET password_hash = '<bcrypt12 hash of the new password>'"
echo "  WHERE email = 'superadmin@knowledgeland.edu.ng';"
echo
echo "⚠ Do NOT paste these values into chat, issues, or commit them."
