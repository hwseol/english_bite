@echo off
cd /d "%~dp0"
echo. >> catalog_run.log
echo ==== %date% %time% ==== >> catalog_run.log
set PYTHONIOENCODING=utf-8
".venv\Scripts\python.exe" catalog.py --preseed >> catalog_run.log 2>&1
