#!/usr/bin/env powershell
<#
KLC CBT Suite v1.0 - GitHub Actions secrets installer (TEMPLATE)

WHY THIS FILE EXISTS
  .github/workflows/build.yml needs 15 repo secrets (plus the optional
  KLC_DB_POOLER_URL fallback). This script reads your LOCAL
  src\main\resources\config.properties (which is gitignored and never
  leaves this machine) and pushes all of them. Nothing is printed to the
  console - only secret NAMES and set/missing status.

HOW TO RUN (Windows PowerShell 5.1 compatible - no gh CLI required)
  1. Make sure src\main\resources\config.properties exists and is filled in
     (copy config.properties.example if needed).
  2. Get a GitHub token with write access to this repo's Actions secrets:
        classic PAT with the "repo" scope, or
        fine-grained PAT with "Actions: Read and write" on this repo.
     (GitHub.com -> Settings -> Developer settings -> Personal access tokens)
  3. Run:
        powershell -ExecutionPolicy Bypass -File tools\setup_github_secrets.template.ps1
     and paste the token when prompted (hidden input).
     Optional:
        powershell -ExecutionPolicy Bypass -File tools\setup_github_secrets.template.ps1 -Token ghp_xxx
        powershell -ExecutionPolicy Bypass -File tools\setup_github_secrets.template.ps1 -DryRun

SAFETY
  - This file contains NO credential values - safe to commit.
  - If you make a copy with values filled in, name it
    tools\setup_github_secrets.ps1 - that path is gitignored.
  - After a successful run you can delete any local copy with values.
#>
param(
    [string]$Token = "",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# Repo root = parent of this script's folder
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$cfgPath = Join-Path $repoRoot "src\main\resources\config.properties"
if (-not (Test-Path $cfgPath)) {
    Write-Host "ERROR: $cfgPath not found."
    Write-Host "Copy src\main\resources\config.properties.example to config.properties and fill it in first."
    exit 1
}

# ---- read a property from the local config (never echoed) ---------------
function Get-Prop([string]$key) {
    foreach ($line in Get-Content $cfgPath) {
        if ($line.TrimStart().StartsWith("#")) { continue }
        $eq = $line.IndexOf("=")
        if ($eq -lt 1) { continue }
        $k = $line.Substring(0, $eq).Trim()
        if ($k -eq $key) { return $line.Substring($eq + 1).Trim() }
    }
    return ""
}

# ---- map local config -> the EXACT secret names build.yml consumes ------
$poolerUrl  = Get-Prop "supabase.db.pooler.url"
$dbHost = ""; $dbPort = ""
if ($poolerUrl -match "jdbc:postgresql://([^:/]+):(\d+)/") {
    $dbHost = $matches[1]; $dbPort = $matches[2]
}

$secrets = [ordered]@{
    "KLC_SUPABASE_URL"        = (Get-Prop "supabase.url")
    "KLC_SUPABASE_ANON_KEY"   = (Get-Prop "supabase.key")
    "KLC_DB_HOST"             = $dbHost
    "KLC_DB_PORT"             = $dbPort
    # Optional fallback (CI parses host/port from it when the two above
    # are absent) - harmless to always set:
    "KLC_DB_POOLER_URL"       = (Get-Prop "supabase.db.pooler.url")
    "KLC_DB_USER"             = (Get-Prop "supabase.db.pooler.user")
    "KLC_DB_PASS"             = (Get-Prop "supabase.db.pooler.password")
    "KLC_CODE_SUPER_ADMIN"    = (Get-Prop "code.super_admin")
    "KLC_CODE_ADMIN"          = (Get-Prop "code.admin")
    "KLC_CODE_STUDENT"        = (Get-Prop "code.student")
    "KLC_SMTP_HOST"           = (Get-Prop "smtp.host")
    "KLC_SMTP_PORT"           = (Get-Prop "smtp.port")
    "KLC_SMTP_USER"           = (Get-Prop "smtp.user")
    "KLC_SMTP_PASS"           = (Get-Prop "smtp.pass")
    "KLC_SMTP_FROM_NAME"      = (Get-Prop "smtp.from.name")
    "KLC_SMTP_FROM_EMAIL"     = (Get-Prop "smtp.from.email")
}

$missing = @()
foreach ($name in $secrets.Keys) {
    if ([string]::IsNullOrWhiteSpace($secrets[$name])) { $missing += $name }
}
if ($missing.Count -gt 0) {
    Write-Host "WARNING - these secrets have NO value in your local config.properties:"
    foreach ($m in $missing) { Write-Host "   $m" }
    Write-Host "Fill them in locally first, then re-run. Continuing with the rest."
    if ($DryRun) { exit 1 }
}

# ---- resolve repo owner/name from git remote ----------------------------
$owner = ""; $repo = ""
try {
    $remote = (git remote get-url origin).Trim()
    if ($remote -match "github\.com[:/]([^/]+)/([^/.]+)") {
        $owner = $matches[1]; $repo = $matches[2]
    }
} catch { }
if ($owner -eq "" -or $repo -eq "") {
    Write-Host "ERROR: could not determine owner/repo from 'git remote get-url origin'."
    Write-Host "Run this from inside the repo clone (remote origin -> https://github.com/OWNER/REPO.git)."
    exit 1
}
Write-Host "Repository: $owner/$repo"

if ($DryRun) {
    Write-Host "`nDRY RUN - nothing will be pushed. Status per secret:"
    foreach ($name in $secrets.Keys) {
        if ([string]::IsNullOrWhiteSpace($secrets[$name])) {
            Write-Host ("  {0,-24} MISSING (no local value)" -f $name)
        } else {
            Write-Host ("  {0,-24} ready (value present locally, not shown)" -f $name)
        }
    }
    exit 0
}

# ---- get a token: param > gh CLI > secure prompt ------------------------
$plainToken = ""
function ConvertTo-PlainText([SecureString]$sec) {
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec)
    try { return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}

if ($Token.Trim() -ne "") {
    $plainToken = $Token.Trim()
} else {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($gh) {
        $ghToken = (gh auth token 2>$null)
        if ($LASTEXITCODE -eq 0 -and $ghToken) { $plainToken = $ghToken.Trim() }
    }
}
if ($plainToken -eq "") {
    Write-Host "Enter a GitHub token (classic 'repo' scope, or fine-grained with Actions write on $owner/$repo)."
    Write-Host "Input is hidden. The token is used only for this run and is not stored."
    $sec = Read-Host "GitHub token" -AsSecureString
    $plainToken = ConvertTo-PlainText $sec
}

# ---- push secrets --------------------------------------------------------
if (-not $DryRun) {
    $gh = Get-Command gh -ErrorAction SilentlyContinue
    if ($gh) {
        Write-Host "`nUsing gh CLI to set secrets..."
        $fail = 0
        foreach ($name in $secrets.Keys) {
            if ([string]::IsNullOrWhiteSpace($secrets[$name])) { continue }
            gh secret set $name --body $secrets[$name]
            if ($LASTEXITCODE -ne 0) { $fail++; Write-Host "   FAILED: $name" }
            else { Write-Host ("  {0,-24} SET" -f $name) }
        }
        if ($fail -gt 0) { exit 1 }
    } else {
        Write-Host "`nUsing GitHub API (Invoke-RestMethod) to set secrets..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        $headers = @{
            Authorization        = "Bearer $plainToken"
            "X-GitHub-Api-Version" = "2022-11-28"
            "Accept"             = "application/vnd.github+json"
            "Content-Type"       = "application/json"
        }
        $fail = 0
        foreach ($name in $secrets.Keys) {
            if ([string]::IsNullOrWhiteSpace($secrets[$name])) { continue }
            $b64 = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($secrets[$name]))
            $body = @{ secret = $b64 } | ConvertTo-Json -Compress
            $uri  = "https://api.github.com/repos/$owner/$repo/actions/secrets/$name"
            try {
                $null = Invoke-RestMethod -Method Put -Uri $uri -Headers $headers -Body $body
                Write-Host ("  {0,-24} SET" -f $name)
            } catch {
                $fail++
                Write-Host ("  {0,-24} FAILED - {1}" -f $name, $_.Exception.Message)
            }
        }
        if ($fail -gt 0) {
            Write-Host "`nSome secrets failed. Check the token has Actions write access and re-run."
            exit 1
        }
    }
}

Write-Host ""
Write-Host "Done. All secrets for .github/workflows/build.yml are in place."
Write-Host "Next: the next 'git push origin main' triggers the first full CI run"
Write-Host "(Supabase migrate + JAR build + latest-build pre-release)."
Write-Host "Reminder: rotate anything that was ever pasted into chat (see SECURITY_CREDENTIALS.md)."
