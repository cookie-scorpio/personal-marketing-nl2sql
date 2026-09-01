$ErrorActionPreference = 'Stop'

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$frontendDirectory = Join-Path $repositoryRoot 'crm\frontend'

if (-not (Get-Command 'npm.cmd' -ErrorAction SilentlyContinue)) {
    throw 'npm was not found. Install Node.js 20 or later and add it to PATH.'
}

Push-Location $frontendDirectory
try {
    if (-not (Test-Path -LiteralPath (Join-Path $frontendDirectory 'node_modules'))) {
        Write-Host 'Installing frontend dependencies from package-lock.json.'
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) {
            throw 'Frontend dependency installation failed.'
        }
    }
    & npm.cmd run dev
} finally {
    Pop-Location
}
