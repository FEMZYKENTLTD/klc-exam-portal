#requires -RunAsAdministrator
<#
.SYNOPSIS
  Reverses Enable-KlcKiosk.ps1 for the exam account (policies removed,
  auto-launch removed). The exam account itself is kept unless -RemoveUser.
#>
param([string]$ExamUser = "KLCExam", [switch]$RemoveUser)
$sid = (Get-LocalUser -Name $ExamUser -ErrorAction Stop).SID.Value
$hive = "HKU:\$sid"; $loadedByUs = $false
if (-not (Test-Path $hive)) {
    $profile = (Get-CimInstance Win32_UserProfile -Filter "SID='$sid'").LocalPath
    reg load "HKU\$sid" "$profile\NTUSER.DAT" | Out-Null; $loadedByUs = $true
}
$pol = "$hive\Software\Microsoft\Windows\CurrentVersion\Policies"
foreach ($v in "DisableTaskMgr","DisableLockWorkstation","DisableChangePassword","HideFastUserSwitching") {
    Remove-ItemProperty "$pol\System" $v -ErrorAction SilentlyContinue }
foreach ($v in "NoLogoff","NoRun","NoControlPanel") {
    Remove-ItemProperty "$pol\Explorer" $v -ErrorAction SilentlyContinue }
Remove-ItemProperty "$hive\Software\Microsoft\Windows\CurrentVersion\Run" "KLC-CBT" -ErrorAction SilentlyContinue
if ($loadedByUs) { [gc]::Collect(); reg unload "HKU\$sid" | Out-Null }
if ($RemoveUser) { Remove-LocalUser -Name $ExamUser }
Write-Host "KLC kiosk lockdown removed for '$ExamUser'."
