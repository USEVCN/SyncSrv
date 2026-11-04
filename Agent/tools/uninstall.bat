@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0agent-control.ps1" -Action uninstall
set "agent_result=%ERRORLEVEL%"
if not "%agent_result%"=="0" echo Uninstall failed. Registration and data may still be present; review the error above.
pause
exit /b %agent_result%
