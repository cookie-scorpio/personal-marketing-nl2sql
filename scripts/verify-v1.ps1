#requires -Version 7.0
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Username = 'director01',
    [string]$Password = 'Demo@123'
)

$ErrorActionPreference = 'Stop'

# 该脚本仅使用演示账号与模拟数据，验证最重要的交互分支，不打印JWT或密码。
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json' `
    -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)
$headers = @{ Authorization = 'Bearer ' + $login.data.access_token }

function Send-Json([string]$Path, [hashtable]$Body) {
    return (Invoke-RestMethod -Method Post -Uri ($BaseUrl + $Path) -Headers $headers `
        -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 8)).data
}

function Wait-Query([string]$TaskId) {
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        $status = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/queries/$TaskId/status" -Headers $headers).data
        if ($status.status -in @('SUCCESS', 'FAILED', 'ASKING', 'CONFIRMING', 'CANCELLED')) { return $status }
        Start-Sleep -Milliseconds 300
    }
    throw "任务等待超时：$TaskId"
}

function Submit-Query([string]$Text) {
    $created = Send-Json '/api/v1/queries' @{
        session_id = [guid]::NewGuid().ToString(); query_text = $Text; preferred_display = 'AUTO'
    }
    return Wait-Query $created.task_id
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (!$Condition) { throw $Message }
}

$normal = Submit-Query '统计近30天各机构客户交易金额'
Assert-True ($normal.status -eq 'SUCCESS') '普通查询未成功'
Assert-True (@($normal.result.charts).Count -gt 0) '分组查询未返回图表'
Assert-True (@($normal.result.analysis.insights).Count -gt 0) '分组查询未返回分析洞察'
Write-Output 'PASS 普通查询、图表与分析'

$missing = Submit-Query '统计各机构客户交易金额'
Assert-True ($missing.status -eq 'ASKING') '缺失时间未触发反问'
$null = Send-Json "/api/v1/conversations/$($missing.session_id)/messages" @{
    task_id = $missing.task_id; question_id = $missing.question.question_id
    answer_text = '近30天'; selected_options = @()
}
$clarified = Wait-Query $missing.task_id
Assert-True ($clarified.status -eq 'SUCCESS') '补充时间后未完成查询'
Write-Output 'PASS 缺失信息反问与补充闭环'

$conflict = Submit-Query '统计近30天和近半年客户交易金额'
Assert-True ($conflict.status -eq 'ASKING' -and $conflict.question.type -eq 'CONFLICT') '矛盾条件未触发澄清'
$null = Send-Json "/api/v1/conversations/$($conflict.session_id)/messages" @{
    task_id = $conflict.task_id; question_id = $conflict.question.question_id
    answer_text = '近30天'; selected_options = @()
}
$resolved = Wait-Query $conflict.task_id
Assert-True ($resolved.status -eq 'SUCCESS') '矛盾澄清后未完成查询'
Write-Output 'PASS 矛盾识别与澄清闭环'

$risky = Submit-Query '找出所有客户名单'
Assert-True ($risky.status -eq 'CONFIRMING') '大范围查询未触发确认'
Assert-True (@($risky.confirmation.reasons).Count -gt 0) '确认信息缺少风险原因'
$null = Send-Json "/api/v1/queries/$($risky.task_id)/confirmations" @{
    confirm_token = $risky.confirmation.confirm_token; decision = 'CONFIRM'
}
$confirmed = Wait-Query $risky.task_id
Assert-True ($confirmed.status -eq 'SUCCESS') '确认后未执行保存的查询计划'
Write-Output 'PASS 风险确认与执行'

$rejected = Submit-Query '找出所有客户名单'
$cancelled = Send-Json "/api/v1/queries/$($rejected.task_id)/confirmations" @{
    confirm_token = $rejected.confirmation.confirm_token; decision = 'REJECT'
}
Assert-True ($cancelled.status -eq 'CANCELLED') '拒绝执行未取消任务'
Write-Output 'PASS 拒绝风险查询'

$history = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/query-history?page_no=1&page_size=2" -Headers $headers).data
Assert-True (@($history.items).Count -le 2 -and $history.page_size -eq 2) '历史分页参数未生效'
Write-Output 'PASS 历史分页'
Write-Output 'v1.0 核心闭环验收通过。'
