<#
.SYNOPSIS
  T1-13b per-signal search cost: pull device logcat evidence and fill the evidence table.

.DESCRIPTION
  1. Pull MM-Capture logs via adb (-b all -v threadtime -s MM-Capture:V). Output is redirected
     at byte level, so no console code page decoding is involved. logcat -d is a ring-buffer
     snapshot, so the pull is MERGED into the existing dump, never overwritten (an earlier pull
     can hold windows the newer one has already rolled over);
  2. Parse signal-cost lines (one line per completed window of N samples per signal) and
     aggregate per signal: avg = mean of window averages; p95 / max = worst window value;
  3. Read the desktop baseline from the section 2 table of the research doc (single source of
     truth; the numbers are not duplicated here);
  4. Print ASCII-only comparison rows and write a Markdown table under
     app/build/replay-work/out/ (plus the raw dump next to it);
  5. -UpdateDoc: rewrite the table block in section 6 of the research doc (UTF-8, no BOM,
     keeps the original line ending style);
  6. Mirror the raw dump and the generated md into docs/verification/t1-13b/ when that directory
     exists, because app/build/ is gitignored and the copies are what actually survives.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File tools\t1-13b-device-signal-cost.ps1 -UpdateDoc

.EXAMPLE
  # offline self-test: parse an existing dump, patch a doc copy
  powershell -ExecutionPolicy Bypass -File tools\t1-13b-device-signal-cost.ps1 -LogFile _tmp_check\fake-log.txt -Doc _tmp_check\doc-copy.md -UpdateDoc

.NOTES
  KEEP THIS FILE PURE ASCII (comments included). Windows PowerShell 5.1 reads a BOM-less .ps1
  as ANSI (GBK on zh-CN). Chinese UTF-8 bytes then decode misaligned and a stray continuation
  byte can swallow the following ASCII character, line ending included. That silently joins a
  comment with the next code line, the assignment never runs, and [regex]::Match coerces the
  resulting $null pattern into an empty one that "matches" every line (bogus empty signal name).
  Chinese text needed in the output is therefore built from \uXXXX escapes by Cn().
#>
param(
    [switch]$UpdateDoc,
    [string]$LogFile = '',
    [string]$Doc = '',
    [int]$MinWindows = 1
)

$ErrorActionPreference = 'Stop'

# ---- Chinese labels, built from \uXXXX escapes (see .NOTES: do not inline literals) ----
function Cn([string]$esc) { [regex]::Unescape($esc) }
$H_SIGNAL = Cn '\u4FE1\u53F7'                          # signal
$H_DESK   = Cn '\u672C\u6587\u684C\u9762\u5E73\u5747'   # desktop average
$H_DEV    = Cn '\u771F\u673A\u5E73\u5747'               # device average
$H_RATIO  = Cn '\u5B9E\u6D4B\u500D\u5DEE'               # measured ratio
$H_P95    = (Cn '\u771F\u673A') + 'P95'                 # device P95
$H_WIN    = Cn '\u7A97\u53E3\u6570'                     # window count

$repo = Split-Path -Parent $PSScriptRoot
if (-not $Doc) { $Doc = Join-Path $repo 'docs\recognition\research\research-t1-13b-per-signal-cost.md' }
$outDir = Join-Path $repo 'app\build\replay-work\out'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$utf8 = New-Object System.Text.UTF8Encoding($false)
function Read-Utf8([string]$p) { [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8) }
function Write-Utf8([string]$p, [string]$t) { [System.IO.File]::WriteAllText($p, $t, $utf8) }
function Fail([string]$msg) { Write-Host ('ERROR: ' + $msg); exit 1 }
function Row([string]$n, [string]$d, [string]$a, [string]$r, [string]$p, [string]$w) {
    '| `' + $n + '` | ' + $d + ' | ' + $a + ' | ' + $r + ' | ' + $p + ' | ' + $w + ' |'
}

# ---- 1. get the log (device, or an existing dump) ----
$logPath = Join-Path $outDir 't1-13b-device-log.txt'
if ($LogFile) {
    if (-not (Test-Path $LogFile)) { Fail ('log file not found: ' + $LogFile) }
    $logPath = (Resolve-Path $LogFile).Path
    Write-Host ('log source: file ' + $logPath)
} else {
    $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (-not (Test-Path $adb)) { $adb = 'adb' }
    $devs = ''
    try { $devs = (& $adb devices -l) -join ' ' } catch { Fail ('adb not runnable: ' + $_.Exception.Message) }
    $tid = [regex]::Match($devs, 'transport_id:(\d+)').Groups[1].Value
    if (-not $tid) { Fail 'no adb device (check: adb devices)' }
    Write-Host ('pulling logcat via transport_id=' + $tid)
    $rawPath = Join-Path $outDir 't1-13b-device-log-raw.txt'
    try {
        Start-Process -FilePath $adb `
            -ArgumentList @('-t', $tid, 'logcat', '-d', '-b', 'all', '-v', 'threadtime', '-s', 'MM-Capture:V') `
            -RedirectStandardOutput $rawPath -NoNewWindow -Wait
    } catch { Fail ('adb logcat failed: ' + $_.Exception.Message) }

    # logcat -d is a SNAPSHOT of a ring buffer: it silently drops the oldest lines, so a later pull
    # can contain fewer completed windows than an earlier one (seen 2026-09-13: a re-pull lost the
    # launch_start window and the table write-back regressed to 3/7 signals). Merge instead of
    # overwrite; exact duplicate lines are dropped so each window is still counted once.
    $merged = New-Object System.Collections.Generic.List[string]
    $seen = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($p in @($logPath, $rawPath)) {
        if (-not (Test-Path $p)) { continue }
        foreach ($l in [System.IO.File]::ReadAllLines($p, [System.Text.Encoding]::UTF8)) {
            if ($seen.Add($l)) { $merged.Add($l) }
        }
    }
    Write-Utf8 $logPath (($merged.ToArray() -join "`r`n") + "`r`n")
    $fresh = ([System.IO.File]::ReadAllLines($rawPath, [System.Text.Encoding]::UTF8)).Count
    Write-Host ('log source: device -> ' + $logPath + ' (pulled ' + $fresh + ' line(s), merged ' + $merged.Count + ')')
    if ($fresh -eq 0) {
        Write-Host 'WARNING: the pull returned 0 line(s) - app not running, or no capture session?'
        Write-Host '         Reporting the last known dump; it is NOT refreshed device data.'
    }
}

# ---- 2. parse signal-cost lines ----
# Sample line, with the Chinese words shown as placeholders here:
#   <TAG>: <label>: launch_start <lian> 100 <ci><fwcomma> P95 260.0ms<fwcomma> <avg> 255.0ms<fwcomma> <max> 300.2ms
# Only ASCII is anchored (signal name, the numbers, the literal "P95"); Chinese separators are
# absorbed by \S*, so the parse survives any decoding of the log text. The neighbouring
# frame-timing line has no ASCII token right after a colon, so it can never match here.
$rx = ':\s*(?<name>[A-Za-z_][A-Za-z0-9_]*)\s+\S+\s+(?<n>\d+)\s+\S*P95\s+(?<p95>[\d.]+)ms\S*\s*(?<avg>[\d.]+)ms\S*\s*(?<max>[\d.]+)ms'
if ($rx.Length -lt 40) {
    # Guard: if the literal above ever fails to load (e.g. the file gets re-saved with Chinese
    # comments and is read as ANSI), $rx would be $null, .NET would coerce it to an empty
    # pattern and every line would "match" with empty groups. Fail loudly instead.
    Fail 'internal error: regex literal did not load (keep this script ASCII-only)'
}
$samples = [ordered]@{}
foreach ($line in [System.IO.File]::ReadAllLines($logPath, [System.Text.Encoding]::UTF8)) {
    $m = [regex]::Match($line, $rx)
    if (-not $m.Success) { continue }
    $name = $m.Groups['name'].Value
    if (-not $samples.Contains($name)) { $samples[$name] = New-Object System.Collections.ArrayList }
    [void]$samples[$name].Add([pscustomobject]@{
        Window = [int]$m.Groups['n'].Value
        P95    = [double]$m.Groups['p95'].Value
        Avg    = [double]$m.Groups['avg'].Value
        Max    = [double]$m.Groups['max'].Value
    })
}
$totalSamples = 0
foreach ($k in $samples.Keys) { $totalSamples += $samples[$k].Count }
if ($totalSamples -eq 0) {
    Write-Host ('no signal-cost lines found in: ' + $logPath)
    Write-Host 'hints:'
    Write-Host '  1) install the debug APK built from this branch (gradlew :app:assembleDebug)'
    Write-Host '  2) start a capture session in MasterMechanic (recognition must be running)'
    Write-Host '  3) walk the flow, stay 1-2 min on each screen'
    Write-Host '     (steady throttle = 1000ms, 1 signal per round -> 100 samples ~= 100s)'
    Write-Host '  4) rerun this script (logcat keeps lines after the session stops)'
    Fail 'nothing to report'
}
Write-Host ('parsed ' + $totalSamples + ' window sample(s) for ' + $samples.Count + ' signal(s)')

$agg = [ordered]@{}
foreach ($name in $samples.Keys) {
    $s = @($samples[$name])
    $agg[$name] = [pscustomobject]@{
        Windows = $s.Count
        Avg     = ($s | Measure-Object -Property Avg -Average).Average
        P95     = ($s | Measure-Object -Property P95 -Maximum).Maximum
        Max     = ($s | Measure-Object -Property Max -Maximum).Maximum
    }
}

# ---- 3. desktop baseline (section 2 table; its 13 columns exclude the narrow tables) ----
if (-not (Test-Path $Doc)) { Fail ('research doc not found: ' + $Doc) }
$docText = Read-Utf8 $Doc
$baseline = [ordered]@{}
foreach ($line in ($docText -split "`r?`n")) {
    # Use -match (-notmatch does not fill $Matches) and capture the name before the numeric
    # check, because the later -notmatch overwrites $Matches.
    if (-not ($line -match '^\|\s*`(?<n>[a-z_][a-z0-9_]*)`\s*\|')) { continue }
    $n = $Matches['n']
    $parts = $line -split '\|'
    if ($parts.Count -lt 12) { continue }
    $avg = $parts[8].Trim().Trim('*').Trim()
    if ($avg -notmatch '^[\d.]+$') { continue }
    if (-not $baseline.Contains($n)) { $baseline[$n] = [double]$avg }
}
if ($baseline.Count -eq 0) { Fail ('desktop baseline table not found in: ' + $Doc) }
Write-Host ('desktop baseline: ' + $baseline.Count + ' signal(s) from section 2')

# ---- 4. compare and report ----
$names = @()
foreach ($n in $baseline.Keys) { $names += $n }
foreach ($n in $agg.Keys) { if (-not $baseline.Contains($n)) { $names += $n } }

$rows = New-Object System.Collections.ArrayList
$ratios = New-Object System.Collections.ArrayList
$measured = 0
foreach ($n in $names) {
    $desk = if ($baseline.Contains($n)) { $baseline[$n] } else { $null }
    $deskTxt = if ($desk) { ([double]$desk).ToString('0.000').PadLeft(9) } else { 'n/a'.PadLeft(9) }
    $a = if ($agg.Contains($n)) { $agg[$n] } else { $null }
    if ($a -and $a.Windows -ge $MinWindows) {
        $measured++
        $ratio = if ($desk) { $a.Avg / [double]$desk } else { $null }
        $ratioTxt = if ($ratio) { 'x' + $ratio.ToString('0.0') } else { 'n/a' }
        if ($ratio) { [void]$ratios.Add($ratio) }
        Write-Host ('{0,-14} desktop={1}ms  device_avg={2,9}ms  ratio={3,-7} p95={4,9}ms  max={5,9}ms  windows={6}' -f `
            $n, $deskTxt, $a.Avg.ToString('0.00'), $ratioTxt, $a.P95.ToString('0.00'), $a.Max.ToString('0.00'), $a.Windows)
        [void]$rows.Add([pscustomobject]@{
            Name = $n; Desktop = $deskTxt.Trim(); Avg = $a.Avg.ToString('0.00')
            Ratio = $ratioTxt; P95 = $a.P95.ToString('0.00'); Win = [string]$a.Windows
        })
    } else {
        Write-Host ('{0,-14} desktop={1}ms  device_avg=pending' -f $n, $deskTxt)
        [void]$rows.Add([pscustomobject]@{ Name = $n; Desktop = $deskTxt.Trim(); Avg = ''; Ratio = ''; P95 = ''; Win = '' })
    }
}

$mdHeader = '| ' + $H_SIGNAL + ' | ' + $H_DESK + ' (ms) | ' + $H_DEV + ' (ms) | ' + $H_RATIO + ' | ' + $H_P95 + ' (ms) | ' + $H_WIN + ' |'
$mdSep = '| --- | --- | --- | --- | --- | --- |'
$mdLines = @()
$mdLines += '# T1-13b device per-signal cost (evidence for research doc section 6)'
$mdLines += '# source log: ' + $logPath
$mdLines += '# generated:  ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
$mdLines += ''
$mdLines += $mdHeader
$mdLines += $mdSep
foreach ($r in $rows) { $mdLines += (Row $r.Name $r.Desktop $r.Avg $r.Ratio $r.P95 $r.Win) }
if ($ratios.Count) {
    $mean = ($ratios | Measure-Object -Average).Average
    $mdLines += ''
    $mdLines += ('# mean ratio over {0} measured signal(s) = x{1}' -f $ratios.Count, $mean.ToString('0.0'))
    Write-Host ('summary: measured={0} pending={1} mean_ratio=x{2}' -f $measured, ($names.Count - $measured), $mean.ToString('0.0'))
} else {
    Write-Host ('summary: measured={0} pending={1}' -f $measured, ($names.Count - $measured))
}
$mdOut = Join-Path $outDir 't1-13b-per-signal-cost-device.md'
Write-Utf8 $mdOut (($mdLines -join "`r`n") + "`r`n")
Write-Host ('wrote: ' + $mdOut)

# ---- 4b. mirror both artifacts into the evidence dir (app/build/ is gitignored) ----
$archDir = Join-Path $repo 'docs\verification\t1-13b'
if (Test-Path $archDir) {
    $archLog = Join-Path $archDir 't1-13b-device-log.txt'
    $existing = Resolve-Path -LiteralPath $archLog -ErrorAction SilentlyContinue
    if (-not $existing -or ($existing.Path -ne $logPath)) {
        Copy-Item -LiteralPath $logPath -Destination $archLog -Force
    }
    Copy-Item -LiteralPath $mdOut -Destination (Join-Path $archDir 't1-13b-device-signal-cost.md') -Force
    Write-Host ('archived to: ' + $archDir)
}

# ---- 5. write the table back into section 6 of the research doc ----
if ($UpdateDoc) {
    if ($measured -eq 0) { Fail 'no measured signal; doc not updated' }
    $nl = if ($docText.Contains("`r`n")) { "`r`n" } else { "`n" }
    $lines = New-Object System.Collections.ArrayList
    foreach ($l in ($docText -split "`r?`n")) { [void]$lines.Add($l) }

    # Collect runs of consecutive table lines. The target run is the one whose header carries a
    # device-average cell; the section 2 header says "desktop-estimated average" instead, so the
    # two tables cannot be confused.
    $runs = New-Object System.Collections.ArrayList
    $i = 0
    while ($i -lt $lines.Count) {
        if ($lines[$i].StartsWith('|')) {
            $j = $i
            while ($j -lt $lines.Count -and $lines[$j].StartsWith('|')) { $j++ }
            [void]$runs.Add([pscustomobject]@{ Start = $i; End = $j - 1 })
            $i = $j
        } else { $i++ }
    }
    $target = $null
    foreach ($r in $runs) {
        for ($k = $r.Start; $k -le $r.End; $k++) {
            foreach ($c in ($lines[$k] -split '\|')) {
                if ($c.Trim().StartsWith($H_DEV)) { $target = $r }
            }
        }
    }
    if (-not $target) { Fail ('target table (header with device-average column) not found in: ' + $Doc) }

    $block = New-Object System.Collections.ArrayList
    [void]$block.Add($mdHeader)
    [void]$block.Add($mdSep)
    foreach ($r in $rows) { [void]$block.Add((Row $r.Name $r.Desktop $r.Avg $r.Ratio $r.P95 $r.Win)) }

    $count = $target.End - $target.Start + 1
    for ($x = 0; $x -lt $count; $x++) { $lines.RemoveAt($target.Start) }
    $lines.InsertRange($target.Start, [System.Collections.ArrayList]$block)

    $newText = $lines -join $nl
    if ($newText -eq $docText) {
        Write-Host 'doc already up to date'
    } else {
        Write-Utf8 $Doc $newText
        Write-Host ('doc updated: ' + $Doc + ' (rows=' + $rows.Count + ', lines ' + ($target.Start + 1) + '-' + ($target.Start + $block.Count) + ')')
    }
    Write-Host 'note: the conversion factor in section 3.3 was revised by hand (x17 avg / x28 slow); refresh it if this run disagrees.'
}
