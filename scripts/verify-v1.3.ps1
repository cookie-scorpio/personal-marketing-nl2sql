param(
    [string]$Python = 'python',
    [switch]$MysqlTests,
    [switch]$ApiRules,
    [switch]$RealModel,
    [string]$BaseUrl = 'http://127.0.0.1:18081',
    [string]$Database = 'pf_nl2sql_v13_test',
    [string]$BudgetFile = '',
    [string]$CaseIds = ''
)
$ErrorActionPreference = 'Stop'
$repo = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ($Database -notmatch '^[A-Za-z0-9_]+_test$') { throw '只允许以_test结尾的隔离数据库' }
if ($RealModel -and (-not $BudgetFile -or -not (Test-Path -LiteralPath $BudgetFile))) {
    throw '真实模型评测需要显式BudgetFile，且测试后端须已启用同一文件；不要重置已有计数'
}
Push-Location $repo
try {
    $argsMaven = @('-f', 'crm/backend/pom.xml', 'test')
    if ($MysqlTests) {
        $argsMaven += '-Dv11.mysql=true'
        # cmd包装的mvn在Windows可能拆开&，这里只使用一个URL参数。
        $argsMaven += "-Dspring.datasource.url=jdbc:mysql://127.0.0.1:3306/${Database}?allowPublicKeyRetrieval=true"
    }
    & mvn @argsMaven
    if ($LASTEXITCODE -ne 0) { throw '后端测试未通过' }
    & $Python -m unittest discover -s scripts/evaluation -p 'test_*.py'
    if ($LASTEXITCODE -ne 0) { throw '评测比较器测试未通过' }
    Push-Location crm/frontend
    try {
        & npm test; if ($LASTEXITCODE -ne 0) { throw '前端回归测试未通过' }
        & npm run build; if ($LASTEXITCODE -ne 0) { throw '前端构建未通过' }
    }
    finally { Pop-Location }
    if ($ApiRules -or $RealModel) {
        $evalArgs = @('scripts/evaluation/evaluate.py', '--base-url', $BaseUrl, '--database', $Database)
        if ($RealModel) { $evalArgs += @('--mode', 'model', '--allow-model', '--budget-file', $BudgetFile) }
        else { $evalArgs += @('--mode', 'rules') }
        if ($CaseIds) { $evalArgs += @('--ids', $CaseIds) }
        & $Python @evalArgs
        if ($LASTEXITCODE -ne 0) { throw 'HTTP测试未全部通过，见tmp/v13/evaluation中的JSON/CSV报告' }
    }
    Write-Host '所选验证项已通过；未选择的MySQL/API/真实模型项目不计作通过。'
} finally { Pop-Location }
