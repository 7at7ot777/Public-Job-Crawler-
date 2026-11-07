@echo off
REM Wrapper for Windows Task Scheduler: ensures the working directory is the
REM project root so .env, data/ and logs/ resolve correctly.
cd /d "%~dp0"
"C:\Program Files\nodejs\node.exe" src\index.js --once
