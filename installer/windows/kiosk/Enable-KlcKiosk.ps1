#requires -RunAsAdministrator
<#
.SYNOPSIS
  KLC CBT v1.0 - OS-level exam kiosk lockdown for a dedicated Windows 10/11
  exam account. This is the supported route to TRUE OS lockdown (Task
  Manager, lock screen, sign-out) that no Java app can enforce alone.

.DESCRIPTION
  Run ONCE per exam PC, signed in as Administrator, BEFORE an exam day:
    1. Creates the local "KLC Exam" account (if missing) - exams run under it.
    2. Applies per-user policies to that account's hive: Disable Task
       Manager, Disable Lock Workstation, Hide Fast User Switching,
       Disable Change Password, remove Run/Settings shortcuts.
    3. Registers KLC CBT Suite to auto-launch at that account's logon.
    4. (Optional, -AssignedAccess) prints the exact Assigned Access steps
       for Windows Pro/Enterprise - the strongest single-app kiosk.

  Pair with the in-app layer (already built in): fullscreen kiosk stage
  (KioskMode), 3-strike focus-loss proctoring, auto-submit + lockout.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File Enable-KlcKiosk.ps1 -AppPath "C:\Program Files\KLC-CBT-Suite\KLC-CBT-Suite.exe"
#>
param(
    [string]$ExamUser = "KLCExam",
    [string]$AppPath  = "C:\Program Files\KLC-CBT-Suite\KLC-CBT-Suite.exe",
    [switch]$AssignedAccess
)

$ErrorActionPreference = "Stop"

# 1. Dedicated exam account (no admin rights, password never expires)
if (-not (Get-LocalUser -Name $ExamUser -ErrorAction SilentlyContinue)) {
    $pw = Read-Host "Set a password for the '$ExamUser' exam account" -AsSecureString
    New-LocalUser -Name $ExamUser -Password $pw -PasswordNeverExpires `
        -AccountNeverExpires -Description "KLC CBT exam kiosk account" | Out-Null
    Write-Host "Created local user '$ExamUser'."
}

# Resolve the exam user's SID and load its hive if not logged on
$sid = (Get-LocalUser -Name $ExamUser).SID.Value
$hive = "HKU:\$sid"
if (-not (Test-Path $hive)) {
    $profile = (Get-CimInstance Win32_UserProfile -Filter "SID='$sid'").LocalPath
    if ($profile -and (Test-Path "$profile\NTUSER.DAT")) {
        reg load "HKU\$sid" "$profile\NTUSER.DAT" | Out-Null
        $loadedByUs = $true
    } else {
        Write-Warning "Exam user has never logged on - log on once as '$ExamUser', sign out, then re-run."
        return
    }
}

# 2. Per-user lockdown policies
$pol = "$hive\Software\Microsoft\Windows\CurrentVersion\Policies"
New-Item -Path "$pol\System"   -Force | Out-Null
New-Item -Path "$pol\Explorer" -Force | Out-Null
Set-ItemProperty "$pol\System"   DisableTaskMgr          -Value 1 -Type DWord
Set-ItemProperty "$pol\System"   DisableLockWorkstation  -Value 1 -Type DWord
Set-ItemProperty "$pol\System"   DisableChangePassword   -Value 1 -Type DWord
Set-ItemProperty "$pol\System"   HideFastUserSwitching   -Value 1 -Type DWord
Set-ItemProperty "$pol\Explorer" NoLogoff                -Value 1 -Type DWord
Set-ItemProperty "$pol\Explorer" NoRun                   -Value 1 -Type DWord
Set-ItemProperty "$pol\Explorer" NoControlPanel          -Value 1 -Type DWord

# 3. Auto-launch the CBT suite at exam-account logon (fullscreen exam flow
#    itself is triggered inside the app; this only starts the app)
New-Item -Path "$hive\Software\Microsoft\Windows\CurrentVersion\Run" -Force | Out-Null
Set-ItemProperty "$hive\Software\Microsoft\Windows\CurrentVersion\Run" "KLC-CBT" -Value "`"$AppPath`""

if ($loadedByUs) { [gc]::Collect(); reg unload "HKU\$sid" | Out-Null }

# 4. Assigned Access guidance (strongest, Windows Pro/Enterprise/Education)
if ($AssignedAccess) {
    Write-Host @"

ASSIGNED ACCESS (single-app kiosk) - finish in Settings:
  Settings > Accounts > Other users > Kiosk (Set up a kiosk)
  > Choose '$ExamUser' > pick the KLC CBT Suite app.
The PC then boots straight into the exam app with no desktop at all.
"@
}
Write-Host "KLC kiosk lockdown applied for '$ExamUser'. Reboot and sign in as '$ExamUser' to test."
Write-Host "Undo any time with: Disable-KlcKiosk.ps1 -ExamUser $ExamUser"
