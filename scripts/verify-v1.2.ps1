#requires -Version 7.0
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Username = 'director01',
    [string]$Password = 'Demo@123'
)
$ErrorActionPreference = 'Stop'
# 只使用明确规则问法和客户澄清，不调用收费模型，不打印密码、JWT或客户资产。
function Assert-True([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function Login([string]$Name) {
    $response = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json; charset=utf-8' -Body (@{username=$Name;password=$Password} | ConvertTo-Json)
    return @{Authorization='Bearer ' + $response.data.access_token}
}
$headers = Login $Username
function Send([string]$Path, [hashtable]$Body, [string]$Key = '') {
    $requestHeaders = $headers.Clone()
    if ($Key) { $requestHeaders['Idempotency-Key'] = $Key }
    $response = Invoke-WebRequest -SkipHttpErrorCheck -Method Post -Uri ($BaseUrl+$Path) -Headers $requestHeaders -ContentType 'application/json; charset=utf-8' -Body ($Body | ConvertTo-Json -Depth 8)
    return $response.Content | ConvertFrom-Json
}
function Wait-Query([string]$Id) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $value = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/queries/$Id/status" -Headers $headers).data
        if ($value.status -in @('SUCCESS','FAILED','ASKING','CONFIRMING','CANCELLED','TIMED_OUT','DEGRADED')) { return $value }
        Start-Sleep -Milliseconds 100
    } while ($watch.Elapsed.TotalSeconds -lt 30)
    throw '验收任务等待超时'
}
$session = [guid]::NewGuid().ToString()
$key = [guid]::NewGuid().ToString()
$body = @{session_id=$session;query_text='统计近30天各机构客户交易金额';preferred_display='AUTO';thinking_enabled=$false}
$first = Send '/api/v1/queries' $body $key
Assert-True ($first.code -eq 0) '提交失败'
$again = Send '/api/v1/queries' $body $key
Assert-True ($again.data.task_id -eq $first.data.task_id) '重复提交未复用同一任务'
$changed = $body.Clone(); $changed.thinking_enabled = $true
$conflict = Send '/api/v1/queries' $changed $key
Assert-True ($conflict.code -eq 409005) '幂等键未拒绝不同请求内容'
$state = Wait-Query $first.data.task_id
Assert-True ($state.status -eq 'SUCCESS' -and !$state.thinking_enabled) '规则查询或任务思考设置错误'
Assert-True (@($state.result.charts).Count -gt 0 -or @($state.result.metrics).Count -gt 0) '未生成图表或单项指标'
Write-Output 'PASS 提交幂等、载荷冲突、任务思考设置、规则图表'

$events = (Invoke-WebRequest -Uri "$BaseUrl/api/v1/queries/$($state.task_id)/events" -Headers $headers).Content
Assert-True ($events -match 'event:status' -and $events -match 'SUCCESS') 'SSE未返回持久化阶段和结果'
$ids = [regex]::Matches($events, '(?m)^id:\s*(\d+)') | ForEach-Object { [long]$_.Groups[1].Value }
Assert-True (@($ids).Count -ge 4) '阶段事件不完整'
$resumeHeaders = $headers.Clone(); $resumeHeaders['Last-Event-ID'] = [string]$ids[-1]
$resume = (Invoke-WebRequest -Uri "$BaseUrl/api/v1/queries/$($state.task_id)/events" -Headers $resumeHeaders).Content
Assert-True ($resume -match 'event:snapshot' -and $resume -notmatch '(?m)^id:') 'SSE续传重复发送旧事件'
$detail = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/conversations/$session" -Headers $headers).data
Assert-True (@($detail.messages).Count -eq 2 -and $detail.context.source_task_id -eq $state.task_id) '会话消息或成功上下文未持久化'
$otherName = if ($Username -eq 'director01') {'manager01'} else {'director01'}
$otherHeaders = Login $otherName
$privateResponse = Invoke-WebRequest -SkipHttpErrorCheck -Uri "$BaseUrl/api/v1/conversations/$session" -Headers $otherHeaders
Assert-True ($privateResponse.StatusCode -eq 404) '其他用户可以读取会话'
Write-Output 'PASS SSE阶段回放与续传、会话持久化、用户隔离'

$identitySession = [guid]::NewGuid().ToString()
$identityBody = @{session_id=$identitySession;query_text='帮我查找一下李先生的资产信息';preferred_display='AUTO'}
$identity = Send '/api/v1/queries' $identityBody ([guid]::NewGuid().ToString())
$asking = Wait-Query $identity.data.task_id
Assert-True ($asking.status -eq 'ASKING' -and $asking.question.type -like 'CUSTOMER_*') '未优先澄清客户身份'
Assert-True (!$asking.result) '客户确认前返回了资产'
$busy = Send '/api/v1/queries' $identityBody ([guid]::NewGuid().ToString())
Assert-True ($busy.code -eq 409006) '同一会话允许两个活动任务'
$cancel = Send "/api/v1/queries/$($asking.task_id)/cancel" @{}
$repeatCancel = Send "/api/v1/queries/$($asking.task_id)/cancel" @{}
Assert-True ($cancel.data.status -eq 'CANCELLED' -and $repeatCancel.data.status -eq 'CANCELLED') '取消不幂等'
Write-Output 'PASS 客户身份优先澄清、会话并发保护、重复取消'
Write-Output 'v1.2 HTTP 核心验收通过；真实SQL终止、并发竞态和复杂AST见MySQL集成测试。'
