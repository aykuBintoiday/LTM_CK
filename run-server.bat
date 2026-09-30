@echo off
cd /d "%~dp0"
java -cp "SmartClassroom-Server.jar;lib/*" server.ServerAdminGUI
pause
