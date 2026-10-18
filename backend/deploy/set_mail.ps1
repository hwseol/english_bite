# Turns on sign-up verification mails + Google sign-in for the EnglishBite services on the server
# (production; the retired dev service is configured too, harmlessly), then restarts them.
#
#   powershell -ExecutionPolicy Bypass -File backend\deploy\set_mail.ps1 -Key <key.pem> -Server <user@host>
#
# Mail account - two ways:
#   (default)  reuse the Gmail account Bujirun already sends its codes with: the settings are copied
#              INSIDE the server from Bujirun's root-only drop-in, so the app password never passes
#              through this PC, the screen or the repository. Only the sender name changes.
#   -Manual    type a Gmail address and paste an app password (read from the clipboard, then cleared;
#              it goes to the server over ssh stdin). App password: Google account -> Security ->
#              2-Step Verification on -> App passwords.
#
# Google sign-in: the OAuth *web* client ID (public) - the same one the app is built with
# (app/build.gradle.kts, googleWebClientId). Default is Bujirun's Google Cloud project.
param(
    [Parameter(Mandatory = $true)][string]$Key,
    [Parameter(Mandatory = $true)][string]$Server,
    [string]$GoogleClientId = "865652175610-pfqom7oppf92bob56eb5c19ac7el80p3.apps.googleusercontent.com",
    [switch]$Manual
)
$ErrorActionPreference = "Stop"

if ($Manual) {
    $address = Read-Host "Gmail address that sends the codes"
    Read-Host "Copy the app password (Ctrl+C on Google's page), then press Enter here" | Out-Null
    $password = ((Get-Clipboard) -join "") -replace "\s", ""
    Set-Clipboard -Value " "
    if ($password.Length -ne 16) {
        throw "The clipboard held $($password.Length) letters; an app password has 16 (spaces don't count). Nothing was sent."
    }
    $mailLines = @"
echo 'Environment=EB_SMTP_HOST=smtp.gmail.com'
echo 'Environment=EB_SMTP_PORT=587'
echo 'Environment=EB_SMTP_USER=$address'
echo 'Environment=EB_SMTP_PASSWORD=$password'
echo 'Environment="EB_SMTP_FROM=EnglishBite <$address>"'
"@
} else {
    # Bujirun's drop-in, with its variable prefix and sender name swapped. sudo reads the root-only file.
    $mailLines = "sudo grep -h 'BUJIRUN_SMTP' /etc/systemd/system/bujirun-api.service.d/smtp.conf | sed -e 's/BUJIRUN_SMTP/EB_SMTP/' -e 's/Buji Run/EnglishBite/'"
}

$script = @"
set -eu
for svc in englishbite-api englishbite-api-dev; do
  d=/etc/systemd/system/`$svc.service.d
  sudo mkdir -p `$d
  {
    echo '[Service]'
    $($mailLines -replace "`r", "")
    echo 'Environment=EB_GOOGLE_CLIENT_ID=$GoogleClientId'
  } | sudo tee `$d/mail.conf >/dev/null
  sudo chmod 600 `$d/mail.conf
done
if ! sudo grep -q EB_SMTP_PASSWORD /etc/systemd/system/englishbite-api.service.d/mail.conf; then
  echo '!! no SMTP settings were found - nothing is configured'; exit 1
fi
sudo systemctl daemon-reload
sudo systemctl restart englishbite-api englishbite-api-dev
sleep 3
echo -n 'prod: '; curl -fsS http://127.0.0.1:8000/auth/config; echo
echo -n 'dev:  '; curl -fsS http://127.0.0.1:8001/auth/config; echo
"@
# Through a BOM-free, LF-only temp file: piping from PowerShell 5.1 prepends a BOM and adds CRs, which
# bash then reads as part of the first and last commands.
$tmp = Join-Path $env:TEMP "eb_set_mail.sh"
[IO.File]::WriteAllText($tmp, ($script -replace "`r", ""), (New-Object Text.UTF8Encoding $false))
cmd /c "ssh -i `"$Key`" $Server bash -s < `"$tmp`""
[IO.File]::Delete($tmp)
$password = $null
Write-Host ""
Write-Host 'Done if both lines say {"email_code":true,"google":true}. Then try sign-up in the dev app with your own email.'
