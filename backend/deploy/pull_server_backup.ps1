# Run daily on the PC (scheduled task EnglishBiteServerBackup): copies the server's newest
# accounts-DB snapshot here, and on Sundays also a tarball of catalog.json + cache/ (the
# processed videos - the server holds the only full copy of those). Keeps 30 DB snapshots
# and 4 weekly tarballs.
$ErrorActionPreference = "Stop"
$key  = "C:\Users\mhmh2\.ssh\englishbite-key.pem"
$srv  = "ec2-user@184.193.203.68"
$dest = "C:\Users\mhmh2\EnglishBiteBackups"
$sshOpts = @("-i", $key, "-o", "StrictHostKeyChecking=no", "-o", "ConnectTimeout=20")

New-Item -ItemType Directory -Force "$dest\users", "$dest\content" | Out-Null

# Take a fresh snapshot first so the pulled copy is today's, not last night's.
ssh @sshOpts $srv "python3 ~/english_bite/backend/deploy/backup_users.py" | Out-Null
$latest = (ssh @sshOpts $srv "ls -1 ~/backups/users-*.db | tail -n 1").Trim()
scp @sshOpts "${srv}:$latest" "$dest\users\" | Out-Null
Get-ChildItem "$dest\users" -Filter "users-*.db" | Sort-Object Name -Descending | Select-Object -Skip 30 | Remove-Item -Force

if ((Get-Date).DayOfWeek -eq "Sunday") {
    $tar = "$dest\content\content-$(Get-Date -Format yyyy-MM-dd).tar.gz"
    # cmd /c so the binary stream isn't re-encoded by PowerShell's pipeline.
    cmd /c "ssh -i `"$key`" -o StrictHostKeyChecking=no $srv `"cd ~/english_bite/backend/translate && tar czf - catalog.json cache`" > `"$tar`""
    Get-ChildItem "$dest\content" -Filter "content-*.tar.gz" | Sort-Object Name -Descending | Select-Object -Skip 4 | Remove-Item -Force
}
Write-Output "backup ok $(Get-Date -Format s)"
