# Run this on the release APK/AAB BEFORE handing it to anyone or uploading it to Play:
#
#   powershell -ExecutionPolicy Bypass -File tools\verify_release.ps1
#
# It fails (exit code 1) when the build is not a proper production release: wrong package or app
# name (a ".dev" debug build), a debuggable app, a version code that Play / the update gate cannot
# accept, a server address other than production inside the app, a signature that is not the upload
# key, a dirty or unpushed repository, or a production server that cannot do what the app offers.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$apk = Join-Path $root "app\build\outputs\apk\release\app-release.apk"
$aab = Join-Path $root "app\build\outputs\bundle\release\app-release.aab"
$PROD_HOST = "184-193-203-68.sslip.io"
$script:failed = 0
function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { Write-Host ("PASS  " + $name) -ForegroundColor Green }
    else { Write-Host ("FAIL  " + $name + $(if ($detail) { "  [" + $detail + "]" } else { "" })) -ForegroundColor Red; $script:failed++ }
}

Check "release APK exists" (Test-Path $apk) $apk
Check "release AAB exists" (Test-Path $aab) $aab
if (-not (Test-Path $apk)) { Write-Host "Build first: .\gradlew.bat :app:assembleRelease :app:bundleRelease"; exit 1 }
if ((Test-Path $aab) -and ((Get-Item $aab).LastWriteTime -lt (Get-Item $apk).LastWriteTime.AddMinutes(-10))) {
    Check "AAB and APK come from the same build" $false "the AAB is more than 10 minutes older than the APK"
}

$tools = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools" | Sort-Object { [version]($_.Name -replace '[^\d\.].*$','') } | Select-Object -Last 1
$aapt2 = Join-Path $tools.FullName "aapt2.exe"
$apksigner = Join-Path $tools.FullName "apksigner.bat"

# --- identity
$badging = & $aapt2 dump badging $apk
$pkg = [regex]::Match(($badging | Select-String "^package:").Line, "name='([^']+)' versionCode='(\d+)'")
$appId, $versionCode = $pkg.Groups[1].Value, [int]$pkg.Groups[2].Value
$label = [regex]::Match(($badging | Select-String "^application-label:").Line, "'(.*)'").Groups[1].Value
Check "package is exactly com.mhmh2.englishbite (not .dev)" ($appId -eq "com.mhmh2.englishbite") $appId
Check "app name is EnglishBite (not Dev)" ($label -eq "EnglishBite") $label
Check "not debuggable" (-not ($badging | Select-String "application-debuggable"))
$cfg = Get-Content (Join-Path $root "docs\app-config.json") -Raw | ConvertFrom-Json
Check "versionCode ($versionCode) is above the published latest ($($cfg.latest_version_code))" ($versionCode -gt [int]$cfg.latest_version_code)
Write-Host "      versionCode $versionCode"

# --- what is inside: the production address, never the dev one
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($apk)
try {
    $text = ""
    foreach ($e in ($zip.Entries | Where-Object { $_.FullName -match '^classes\d*\.dex$' })) {
        $ms = New-Object IO.MemoryStream; $s = $e.Open(); $s.CopyTo($ms); $s.Close()
        $text += [Text.Encoding]::GetEncoding("ISO-8859-1").GetString($ms.ToArray())
    }
} finally { $zip.Dispose() }
Check "app contains the production server address" ($text.Contains("https://$PROD_HOST/"))
Check "app contains NO dev server address" (-not $text.Contains("dev.$PROD_HOST"))

# --- signed with the upload key
$props = @{}; Get-Content (Join-Path $root "keystore.properties") | ForEach-Object { $k, $v = $_ -split '=', 2; $props[$k.Trim()] = $v.Trim() }
$keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
$store = if ([IO.Path]::IsPathRooted($props.storeFile)) { $props.storeFile } else { Join-Path $root $props.storeFile }
$expected = ((& $keytool -list -v -keystore $store -alias $props.keyAlias -storepass $props.storePassword 2>$null | Select-String "SHA1:").Line -replace '.*SHA1:\s*','' -replace ':','').ToLower()
$signed = ((& $apksigner verify --print-certs $apk 2>&1 | Select-String "certificate SHA-1 digest").Line -replace '.*digest:\s*','').ToLower()
Check "signed with the release (upload) key" ($expected -ne "" -and $expected -eq $signed) "expected $expected, apk has $signed"

# --- repository
$dirty = (git status --porcelain --untracked-files=no) -join ""
Check "no uncommitted changes in tracked files" ($dirty -eq "") $dirty
git fetch -q origin 2>$null
Check "everything is pushed (HEAD is on origin/main)" ((git rev-parse HEAD) -eq (git rev-parse origin/main))

# --- the production server can do what this app version offers
try {
    $h = Invoke-RestMethod "https://$PROD_HOST/health" -TimeoutSec 15
    Check "production server healthy and fresh" ($h.status -eq "ok" -and $h.fresh -eq $true) ($h | ConvertTo-Json -Compress)
} catch { Check "production server reachable" $false $_.Exception.Message }
try {
    $c = Invoke-RestMethod "https://$PROD_HOST/auth/config" -TimeoutSec 15
    Check "production offers email codes (sign-up needs them)" ($c.email_code -eq $true)
    Check "production offers Google sign-in" ($c.google -eq $true)
} catch { Check "production has the new sign-up endpoints (/auth/config)" $false "deploy the server first: $($_.Exception.Message)" }

Write-Host ""
if ($script:failed -eq 0) { Write-Host "ALL CHECKS PASSED - safe to hand out / upload (versionCode $versionCode)." -ForegroundColor Green; exit 0 }
Write-Host "$script:failed check(s) FAILED - do not release this build." -ForegroundColor Red; exit 1
