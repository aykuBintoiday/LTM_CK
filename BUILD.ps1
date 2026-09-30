$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

Write-Host "[1/4] Kiem tra JDK..."
& javac -version

if (Test-Path out) { Remove-Item -Recurse -Force out }
New-Item -ItemType Directory -Force out | Out-Null

Write-Host "[2/4] Bien dich Java --release 17..."
$files = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
& javac --release 17 -encoding UTF-8 -d out $files
if ($LASTEXITCODE -ne 0) { throw "Javac failed" }

Write-Host "[3/4] Dong goi Server JAR..."
"Manifest-Version: 1.0`r`nMain-Class: server.ServerAdminGUI`r`n" | Set-Content -Encoding ascii server-manifest.mf
& jar cfm SmartClassroom-Server.jar server-manifest.mf -C out server -C out common

Write-Host "[4/4] Dong goi Client JAR..."
"Manifest-Version: 1.0`r`nMain-Class: client.SmartClassroomClient`r`n" | Set-Content -Encoding ascii client-manifest.mf
& jar cfm SmartClassroom-Client.jar client-manifest.mf -C out client -C out common
Remove-Item server-manifest.mf,client-manifest.mf -ErrorAction SilentlyContinue
Write-Host "BUILD OK - JDK 17" -ForegroundColor Green
