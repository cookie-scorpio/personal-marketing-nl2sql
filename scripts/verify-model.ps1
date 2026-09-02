#requires -Version 7.0
# 对已运行的后端执行少量真实模型验收；调用会消耗已配置的模型额度。
param(
    [string]$BaseUrl = 'http://127.0.0.1:8080',
    [string]$Username = 'director01',
    [string]$Password = 'Demo@123'
)

$ErrorActionPreference = 'Stop'
# 仅手动执行：两条自由问题会消耗已配置的真实模型API额度；不输出JWT、模型密钥或结果明细。
Write-Output '开始真实模型验收：2条DeepSeek查询 + 1条规则查询。'
$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/auth/login" -ContentType 'application/json; charset=utf-8' `
    -Body (@{username=$Username;password=$Password} | ConvertTo-Json)
$headers = @{Authorization='Bearer ' + $login.data.access_token}
$cases = @(
    @{text='比较本季度不同渠道的营销转化率';source='DEEPSEEK'},
    @{text='按月展示今年客户交易金额趋势';source='DEEPSEEK'},
    @{text='统计近30天各机构客户交易金额';source='RULE'}
)
foreach ($case in $cases) {
    $headers['Idempotency-Key'] = [guid]::NewGuid().ToString()
    $task = (Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/queries" -Headers $headers -ContentType 'application/json; charset=utf-8' `
        -Body (@{session_id=[guid]::NewGuid().ToString();query_text=$case.text;preferred_display='AUTO'} | ConvertTo-Json)).data
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    do {
        $state = (Invoke-RestMethod -Uri "$BaseUrl/api/v1/queries/$($task.task_id)/status" -Headers $headers).data
        if ($state.status -in @('SUCCESS','FAILED','ASKING','CONFIRMING','CANCELLED','TIMED_OUT','DEGRADED')) {break}
        Start-Sleep -Milliseconds 500
    } while ($watch.Elapsed.TotalSeconds -lt 150)
    if ($state.status -ne 'SUCCESS') {
        throw "查询未成功：$($case.text)；task=$($task.task_id)；status=$($state.status)；$($state.error.message)"
    }
    if ($state.result.interpretation_source -ne $case.source -or [string]::IsNullOrWhiteSpace($state.result.sql_preview)) {
        throw "查询来源或SQL结果不符合预期：$($task.task_id)"
    }
    Write-Output "PASS $($case.source) | $($case.text) | 返回$(@($state.result.rows).Count)行，$(@($state.result.charts).Count)张图 | $($watch.ElapsedMilliseconds)ms"
}
