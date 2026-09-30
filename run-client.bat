@echo off
cd /d "%~dp0"
java -cp "SmartClassroom-Client.jar;lib/*" client.SmartClassroomClient
pause
