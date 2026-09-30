@echo off
chcp 65001 >nul
cd /d "%~dp0"
where javac >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Chua tim thay javac. Hay cai JDK 17 va them vao PATH.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0BUILD.ps1"
if errorlevel 1 pause
