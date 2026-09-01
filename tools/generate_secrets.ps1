#!/usr/bin/env powershell
# KLC CBT Suite v1.0 - local secret generator (Windows)
# Run:  powershell -ExecutionPolicy Bypass -File tools\generate_secrets.ps1
# Writes config.properties with NEW random registration codes and prints
# the matching `gh secret set` commands. NEVER commit or paste the output.

$ErrorActionPreference = "Stop"

function New-Code([int]$len = 24) {
    $alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    -join (1..$len | ForEach-Object { $alphabet[(Get-Random -Maximum $alphabet.Length)] })
}
function New-Password([int]$len = 20) {
    $bytes = New-Object byte[] $len
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    -join ($bytes | ForEach-Object { "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$%&*-"[$_] % 71 })
}

$codeSuperAdmin = New-Code 28
$codeAdmin      = New-Code 16
$codeStudent    = New-Code 16
$adminPassword  = New-Password 20

$cfgPath = "src\main\resources\config.properties"
$existing = @{}
if (Test-Path $cfgPath) {
    Get-Content $cfgPath | ForEach-Object {
        if ($_ -match "^\s*([^#=]+)=(.*)$") { $existing[$matches[1].Trim()] = $matches[2].Trim() }
    }
    Copy-Item $cfgPath "$cfgPath.bak" -Force
    Write-Host "Backed up existing config.properties -> config.properties.bak"
}

function Get-Or-Placeholder([string]$k, [string]$ph) {
    if ($existing.ContainsKey($k) -and $existing[$k] -notmatch "YOUR_") { $existing[$k] } else { $ph }
}

$lines = @"
supabase.url=$(Get-Or-Placeholder 'supabase.url' 'https://YOUR_PROJECT.supabase.co')
supabase.key=$(Get-Or-Placeholder 'supabase.key' 'YOUR_SUPABASE_ANON_OR_SERVICE_KEY')
supabase.storage.bucket=klc-attachments
supabase.db.url=$(Get-Or-Placeholder 'supabase.db.url' 'jdbc:postgresql://db.YOUR_PROJECT.supabase.co:5432/postgres')
supabase.db.user=$(Get-Or-Placeholder 'supabase.db.user' 'postgres')
supabase.db.password=$(Get-Or-Placeholder 'supabase.db.password' 'YOUR_DB_PASSWORD')
supabase.db.pooler.url=$(Get-Or-Placeholder 'supabase.db.pooler.url' 'jdbc:postgresql://YOUR_POOLER_HOST:6543/postgres')
supabase.db.pooler.user=$(Get-Or-Placeholder 'supabase.db.pooler.user' 'postgres.YOUR_PROJECT_REF')
supabase.db.pooler.password=$(Get-Or-Placeholder 'supabase.db.pooler.password' 'YOUR_DB_PASSWORD')
code.super_admin=$codeSuperAdmin
code.admin=$codeAdmin
code.student=$codeStudent
social.allow_student_staff_dm=false
social.allow_student_attachments=false
smtp.host=$(Get-Or-Placeholder 'smtp.host' 'smtp-relay.brevo.com')
smtp.port=$(Get-Or-Placeholder 'smtp.port' '587')
smtp.user=$(Get-Or-Placeholder 'smtp.user' 'YOUR_BREVO_SMTP_LOGIN')
smtp.pass=$(Get-Or-Placeholder 'smtp.pass' 'YOUR_BREVO_SMTP_KEY')
smtp.from.name=KNOWLEDGE LAND COLLEGE CBT
smtp.from.email=$(Get-Or-Placeholder 'smtp.from.email' 'YOUR_SENDER_EMAIL')
"@

Set-Content -Path $cfgPath -Value $lines -Encoding UTF8
Write-Host "`n✅ Wrote $cfgPath (gitignored - safe). Keep a copy in your password manager.`n"
Write-Host "═══ NEW REGISTRATION CODES ═══"
Write-Host "code.super_admin = $codeSuperAdmin"
Write-Host "code.admin       = $codeAdmin"
Write-Host "code.student     = $codeStudent"
Write-Host "`n═══ NEW SUPER ADMIN PASSWORD (set in Supabase with the BCrypt hash below) ═══"
Write-Host "password = $adminPassword"
Write-Host "`n═══ gh secret set commands (review, then run) ═══"
Write-Host "gh secret set KLC_CODE_SUPER_ADMIN --body `"$codeSuperAdmin`""
Write-Host "gh secret set KLC_CODE_ADMIN       --body `"$codeAdmin`""
Write-Host "gh secret set KLC_CODE_STUDENT     --body `"$codeStudent`""
Write-Host "gh secret set KLC_SUPABASE_URL     --body `"$(Get-Or-Placeholder 'supabase.url' 'https://YOUR_PROJECT.supabase.co')`""
Write-Host "gh secret set KLC_SUPABASE_ANON_KEY --body `"<your anon key>`""
Write-Host "gh secret set KLC_SMTP_USER        --body `"<brevo login>`""
Write-Host "gh secret set KLC_SMTP_PASS        --body `"<brevo smtp key>`""
Write-Host "`nSQL to rotate the super admin password (BCrypt-12 needed):"
Write-Host "  UPDATE users SET password_hash = '<bcrypt12 hash of the new password>'"
Write-Host "  WHERE email = 'superadmin@knowledgeland.edu.ng';"
Write-Host "`n⚠ Do NOT paste these values into chat, issues, or commit them."
