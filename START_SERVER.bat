@echo off
chcp 65001 >nul
cd /d "%~dp0"
where java >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Chua tim thay Java. Hay cai JDK 17.
  pause
  exit /b 1
)
if not exist SmartClassroom-Server.jar (
  echo [INFO] Chua co JAR. Dang build...
  call BUILD.bat
)
java -cp "SmartClassroom-Server.jar;lib/*" server.ServerAdminGUI
if errorlevel 1 pause
