@echo off
cd /d "%~dp0"
echo. >> admin_sync_run.log
echo ==== %date% %time% ==== >> admin_sync_run.log
set PYTHONIOENCODING=utf-8
".venv\Scripts\python.exe" admin_sync.py >> admin_sync_run.log 2>&1
