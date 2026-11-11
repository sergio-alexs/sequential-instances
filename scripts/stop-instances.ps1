# Script para detener todas las instancias de sequential-instances
Write-Host "🛑 Deteniendo instancias de sequential-instances..." -ForegroundColor Yellow

$pids = Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like "*sequential-instances*" } | Select-Object -ExpandProperty ProcessId

if ($pids) {
    foreach ($p in $pids) {
        Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
        Write-Host "Proceso $p detenido." -ForegroundColor Green
    }
    Write-Host "✅ Todas las instancias han sido detenidas." -ForegroundColor Green
} else {
    Write-Host "ℹ️ No se encontraron instancias en ejecución." -ForegroundColor Cyan
}

