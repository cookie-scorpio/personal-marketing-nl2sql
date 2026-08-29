param(
    [string]$Python = 'python',
    [switch]$MysqlTests,
    [switch]$ApiRules,
    [switch]$RealModel,
    [string]$BaseUrl = 'http://127.0.0.1:18081',
    [string]$Database = 'pf_nl2sql_v14_test',
    [string]$BudgetFile = '',
    [string]$CaseIds = ''
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($Database -notmatch '^[A-Za-z0-9_]+_test$') { throw 'Only isolated databases ending in _test are allowed.' }
if ($RealModel -and (-not $BudgetFile -or -not (Test-Path -LiteralPath $BudgetFile))) {
    throw 'Real-model evaluation requires an explicit BudgetFile shared with the test backend.'
}
Push-Location $repo
try {
    $mavenArgs = @('-f', 'crm/backend/pom.xml', 'test')
    if ($MysqlTests) {
        $mavenArgs += '-Dv11.mysql=true'
        $mavenArgs += "-Dspring.datasource.url=jdbc:mysql://127.0.0.1:3306/${Database}?allowPublicKeyRetrieval=true"
    }
    & mvn @mavenArgs
    if ($LASTEXITCODE -ne 0) { throw 'Backend tests failed.' }
    & $Python -m unittest discover -s scripts/evaluation -p 'test_*.py'
    if ($LASTEXITCODE -ne 0) { throw 'Evaluation comparator tests failed.' }
    Push-Location crm/frontend
    try {
        & npm test; if ($LASTEXITCODE -ne 0) { throw 'Frontend regression tests failed.' }
        & npm run build; if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
    if ($ApiRules -or $RealModel) {
        $evalArgs = @('scripts/evaluation/evaluate.py', '--base-url', $BaseUrl, '--database', $Database)
        if ($RealModel) { $evalArgs += @('--mode', 'model', '--allow-model', '--budget-file', $BudgetFile) }
        else { $evalArgs += @('--mode', 'rules') }
        if ($CaseIds) { $evalArgs += @('--ids', $CaseIds) }
        & $Python @evalArgs
        if ($LASTEXITCODE -ne 0) { throw 'HTTP evaluation failed; see tmp/v14/evaluation.' }
    }
    Write-Host 'Selected v1.4 checks passed. Unselected MySQL, API, or real-model checks were not run.'
} finally { Pop-Location }
