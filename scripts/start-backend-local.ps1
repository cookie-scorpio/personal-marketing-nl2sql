# 从 deploy/local/.env 启动 MySQL、Redis 和后端；缺失的本地 JWT 密钥只在当前进程生成。
$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$localDeployDirectory = Join-Path $repositoryRoot 'deploy\local'
$environmentFile = Join-Path $localDeployDirectory '.env'
$environmentTemplate = Join-Path $localDeployDirectory '.env.example'

function Assert-RequiredCommand {
    param([string]$Name, [string]$Guidance)

    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command was not found: ${Name}. $Guidance"
    }
}

Assert-RequiredCommand 'docker' 'Install and start Docker Desktop.'
Assert-RequiredCommand 'mvn.cmd' 'Install JDK 17 and Maven 3.9.16, then add Maven to PATH.'

if (-not (Test-Path -LiteralPath $environmentFile)) {
    Copy-Item -LiteralPath $environmentTemplate -Destination $environmentFile
    Write-Host 'Created local deploy/local/.env from .env.example.'
}

$localSettings = @{}
foreach ($line in Get-Content -LiteralPath $environmentFile) {
    $trimmedLine = $line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmedLine) -or $trimmedLine.StartsWith('#')) {
        continue
    }
    $separatorIndex = $trimmedLine.IndexOf('=')
    if ($separatorIndex -lt 1) {
        throw "Cannot parse configuration line in ${environmentFile}: $line"
    }
    $localSettings[$trimmedLine.Substring(0, $separatorIndex).Trim()] = $trimmedLine.Substring($separatorIndex + 1).Trim()
}

foreach ($settingName in 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'REDIS_PORT', 'REDIS_PASSWORD') {
    if ([string]::IsNullOrWhiteSpace($localSettings[$settingName])) {
        throw "$environmentFile is missing $settingName."
    }
    [Environment]::SetEnvironmentVariable($settingName, $localSettings[$settingName], 'Process')
}

$mysqlPort = [string]$localSettings['MYSQL_PORT']
$mysqlDatabase = [string]$localSettings['MYSQL_DATABASE']
$env:MYSQL_URL = 'jdbc:mysql://127.0.0.1:{0}/{1}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Hong_Kong&allowPublicKeyRetrieval=true&useSSL=false' -f $mysqlPort, $mysqlDatabase
$env:REDIS_HOST = '127.0.0.1'
$env:AUTH_ALLOW_EPHEMERAL_RSA_KEY = 'true'
if ([string]::IsNullOrWhiteSpace($env:JWT_SECRET)) {
    $jwtBytes = New-Object byte[] 48
    $randomNumberGenerator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $randomNumberGenerator.GetBytes($jwtBytes)
    } finally {
        $randomNumberGenerator.Dispose()
    }
    $env:JWT_SECRET = [Convert]::ToBase64String($jwtBytes)
}
if ([string]::IsNullOrWhiteSpace($env:MODEL_PROVIDER)) {
    $env:MODEL_PROVIDER = 'mock'
}

Push-Location $localDeployDirectory
try {
    & docker compose up -d --wait
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Compose could not start MySQL and Redis.'
    }
} finally {
    Pop-Location
}

Write-Host 'Local services are ready. Starting the backend.'
Push-Location (Join-Path $repositoryRoot 'crm\backend')
try {
    & mvn.cmd spring-boot:run
} finally {
    Pop-Location
}
