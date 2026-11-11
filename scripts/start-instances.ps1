# Script para iniciar 3 instancias en ventanas de PowerShell independientes
Write-Host "🚀 Iniciando 3 instancias de Sequential Instances..." -ForegroundColor Cyan

$jarPath = Join-Path $PSScriptRoot "..\target\sequential-instances-0.0.1-SNAPSHOT.jar"

if (-not (Test-Path $jarPath)) {
    Write-Host "⚠️  JAR no encontrado. Compilando y empaquetando..." -ForegroundColor Yellow
    Push-Location (Join-Path $PSScriptRoot "..")
    .\mvnw.cmd package -DskipTests
    Pop-Location
}

# Puerto 8081
Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle = 'Instancia #1 (8081)'; java -jar '$jarPath' --server.port=8081"
Start-Sleep -Seconds 2

# Puerto 8082
Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle = 'Instancia #2 (8082)'; java -jar '$jarPath' --server.port=8082"
Start-Sleep -Seconds 2

# Puerto 8083
Start-Process powershell -ArgumentList "-NoExit", "-Command", "`$host.UI.RawUI.WindowTitle = 'Instancia #3 (8083)'; java -jar '$jarPath' --server.port=8083"

Write-Host "✅ Las 3 instancias han sido lanzadas en ventanas independientes." -ForegroundColor Green
Write-Host "📌 Endpoints disponibles:" -ForegroundColor Cyan
Write-Host "   - http://localhost:8081/instances"
Write-Host "   - http://localhost:8081/instances/me"
Write-Host "   - http://localhost:8081/instances/all"
Write-Host "   - http://localhost:8081/actuator/health"

