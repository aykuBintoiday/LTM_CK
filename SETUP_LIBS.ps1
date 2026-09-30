$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$lib = Join-Path $root "lib"
New-Item -ItemType Directory -Force $lib | Out-Null

function Get-Jar($path, $name) {
    $dest = Join-Path $lib $name
    if (Test-Path $dest) { Write-Host "[OK] $name da ton tai"; return }
    $bases = @(
        "https://repo.maven.apache.org/maven2",
        "https://repo1.maven.org/maven2"
    )
    $lastError = $null
    foreach ($base in $bases) {
        $url = "$base/$path"
        try {
            Write-Host "[DOWNLOAD] $name <- $url"
            Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $dest
            return
        } catch {
            $lastError = $_
            if (Test-Path $dest) { Remove-Item $dest -Force -ErrorAction SilentlyContinue }
        }
    }
    throw "Khong tai duoc $name. Kiem tra Internet. Loi cuoi: $lastError"
}

# MySQL Connector/J cho JDBC.
Get-Jar "com/mysql/mysql-connector-j/8.4.0/mysql-connector-j-8.4.0.jar" "mysql-connector-j-8.4.0.jar"

# Native WebRTC Java.
Get-Jar "dev/onvoid/webrtc/webrtc-java/0.18.0/webrtc-java-0.18.0.jar" "webrtc-java-0.18.0.jar"

if ($env:OS -eq "Windows_NT") {
    Get-Jar "dev/onvoid/webrtc/webrtc-java/0.18.0/webrtc-java-0.18.0-windows-x86_64.jar" "webrtc-java-0.18.0-windows-x86_64.jar"
} else {
    Get-Jar "dev/onvoid/webrtc/webrtc-java/0.18.0/webrtc-java-0.18.0-linux-x86_64.jar" "webrtc-java-0.18.0-linux-x86_64.jar"
}

Write-Host "THIET LAP THU VIEN OK" -ForegroundColor Green
Write-Host "Neu WebRTC Native tren Windows bao loi DLL, cai Microsoft Visual C++ Redistributable x64 moi nhat."
