# =============================================================================
#  stop-old-instance.ps1 -- called by 1-start.cmd before it starts the website.
#
#  WHY: if port 10090 is already taken, it is almost always a PREVIOUS launch of
#  this demo that is still running -- and that instance may be an older jar from
#  another folder (or from before a bug fix). That is exactly how "I fixed it but
#  the browser still shows the old behaviour" happens. So: identify the owner by
#  its command line, report it, and stop it; the launcher then starts the jar that
#  sits in this folder.
#
#  Kept as a separate file on purpose: an inline `powershell -Command "..."` with
#  escaped quotes inside a .cmd unbalances cmd's own quoting and silently swallows
#  the rest of the batch file (measured 2026-10-10: the old instance was killed and
#  the launcher then just stopped -- no restart, no error message).
#
#  Only processes whose command line contains "campusswap-backend" are touched, so
#  an unrelated program that happens to own the port is left alone.
#
#  Exit code: 0 = nothing to do, or the previous instance was stopped.
# =============================================================================
$ErrorActionPreference = 'SilentlyContinue'

$procs = @(Get-CimInstance Win32_Process |
    Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*campusswap-backend*' })

if ($procs.Count -eq 0) {
    Write-Host '      no previous CampusSwap instance found - the port owner is something else'
    exit 0
}

foreach ($p in $procs) {
    $jar = [regex]::Match($p.CommandLine, 'campusswap-backend[^ ]*\.jar').Value
    if (-not $jar) { $jar = 'unknown jar' }
    Write-Host ('      previous CampusSwap instance pid=' + $p.ProcessId + ' jar=' + $jar)
    Stop-Process -Id $p.ProcessId -Force
}

Start-Sleep -Seconds 3
Write-Host '      stopped it - starting the copy in this folder'
exit 0
