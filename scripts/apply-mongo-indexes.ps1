# Script para aplicar los índices y crear la colección en MongoDB
param(
    [string]$ContainerName = "sequential-instances-mongo",
    [string]$MongoUri = "mongodb://localhost:27017/sequential-instances"
)

$scriptPath = Join-Path $PSScriptRoot "mongo-init.js"

if (-not (Test-Path $scriptPath)) {
    Write-Error "No se encontró el archivo $scriptPath"
    exit 1
}

Write-Host "🚀 Aplicando índices en MongoDB..." -ForegroundColor Cyan

# Intentar primero por Docker
$dockerRunning = docker ps --filter "name=$ContainerName" --format "{{.Names}}" 2>$null
if ($dockerRunning -eq $ContainerName) {
    Write-Host "🐳 Aplicando en contenedor Docker '$ContainerName'..." -ForegroundColor Yellow
    Get-Content $scriptPath -Raw | docker exec -i $ContainerName mongosh --quiet
    Write-Host "✅ Índices creados satisfactoriamente en Docker." -ForegroundColor Green
    exit 0
}

# Si no es por Docker, intentar con mongosh local
if (Get-Command mongosh -ErrorAction SilentlyContinue) {
    Write-Host "💻 Aplicando con mongosh local a $MongoUri..." -ForegroundColor Yellow
    mongosh $MongoUri $scriptPath
    Write-Host "✅ Índices creados satisfactoriamente con mongosh." -ForegroundColor Green
    exit 0
}

Write-Host "⚠️ No se detectó el contenedor '$ContainerName' ni el comando 'mongosh' en el sistema." -ForegroundColor Red
Write-Host "Entrega el archivo 'scripts/mongo-init.js' a tu administrador de base de datos (DBA)." -ForegroundColor Yellow

