@echo off
setlocal
chcp 65001 >nul
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1"
set "agent_result=%ERRORLEVEL%"
if not "%agent_result%"=="0" pause
exit /b %agent_result%
