<#
.SYNOPSIS
  Creates browser-kiosk desktop shortcuts for the KLC web portal on a
  legacy (Win7/8) PC. Usage:
  powershell -ExecutionPolicy Bypass -File Make-LegacyShortcuts.ps1 -PortalUrl "https://your-school.example/klc"
#>
param([Parameter(Mandatory=$true)][string]$PortalUrl)
$desktop = [Environment]::GetFolderPath("Desktop")
$shell = New-Object -ComObject WScript.Shell
$browser = @(
  "$env:ProgramFiles\Mozilla Firefox\firefox.exe",
  "${env:ProgramFiles(x86)}\Mozilla Firefox\firefox.exe",
  "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
  "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $browser) { throw "No Firefox/Chrome found - install Firefox 115 ESR (last Win7 version) first." }
$pages = @{
  "KLC Parent Checker"   = "$PortalUrl/parent.html"
  "KLC Invigilator Room" = "$PortalUrl/invigilator.html"
  "KLC Question Upload"  = "$PortalUrl/question_upload.html"
  "KLC Staff Portal"     = "$PortalUrl/index.html"
}
foreach ($name in $pages.Keys) {
  $lnk = $shell.CreateShortcut("$desktop\$name.lnk")
  $lnk.TargetPath = $browser
  $lnk.Arguments = if ($browser -like "*firefox*") { "--kiosk `"$($pages[$name])`"" } else { "--kiosk `"$($pages[$name])`"" }
  $lnk.Save()
  Write-Host "Created '$name' shortcut."
}
