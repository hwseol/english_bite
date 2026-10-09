@echo off
cd /d "%~dp0"
echo. >> admin_sync_run.log
echo ==== %date% %time% ==== >> admin_sync_run.log
set PYTHONIOENCODING=utf-8
rem YouTube changes break yt-dlp every few weeks and the fix is always a newer release, so update it
rem before each run (a couple of seconds when already current). A failed update never blocks the sync.
".venv\Scripts\python.exe" -m pip install -U -q yt-dlp >> admin_sync_run.log 2>&1
".venv\Scripts\python.exe" admin_sync.py >> admin_sync_run.log 2>&1
