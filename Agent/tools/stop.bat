@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0agent-control.ps1" -Action stop
set "agent_result=%ERRORLEVEL%"
if not "%agent_result%"=="0" echo Action failed. Review the error above and upgrade.log.
pause
exit /b %agent_result%
