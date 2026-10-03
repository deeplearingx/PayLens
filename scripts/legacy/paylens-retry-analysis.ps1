$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:9999'
$tradeNo = 'PAY2103338806439190528'
$taskId = '284b0fcc-ddd6-44ca-93fc-19044a23a119'
$password = $null
$token = $null
$rsa = $null

try {
    $health = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 10
    if ($health.status -ne 'UP') { throw 'PayLens health endpoint is not UP.' }

    $loginContext = Invoke-RestMethod -Uri "$base/token/login-content" -Method Post `
        -ContentType 'application/json' -Body '{"clientId":"admin"}' -TimeoutSec 10
    if (-not $loginContext.data.passwordEncrypted) { throw 'Login context is not using RSA password transport.' }

    $docPath = Join-Path $PSScriptRoot '../../_config/sql/README.md'
    $doc = Get-Content -LiteralPath $docPath -Raw
    $match = [regex]::Match($doc, '开发默认密码 \*\*(?<value>[^*]+)\*\*')
    if (-not $match.Success) { throw 'Documented local development credential was not found.' }
    $password = $match.Groups['value'].Value

    $keyResponse = Invoke-RestMethod -Uri "$base/token/public-key" -TimeoutSec 10
    $rsa = [System.Security.Cryptography.RSA]::Create()
    $rsa.ImportFromPem([string]$keyResponse.data)
    $cipher = $rsa.Encrypt([Text.Encoding]::UTF8.GetBytes($password), [Security.Cryptography.RSAEncryptionPadding]::Pkcs1)
    $encodedPassword = [Convert]::ToBase64String($cipher)

    $nonce = Invoke-RestMethod -Uri "$base/nonce/generate" -Method Post -TimeoutSec 10
    $loginHeaders = @{ 'x-nonce' = [string]$nonce.data.nonce; 'x-timestamp' = [string]$nonce.data.timestamp }
    $form = "account=bootx&password=$([uri]::EscapeDataString($encodedPassword))&loginType=password&client=admin"
    $loginResponse = Invoke-WebRequest -Uri "$base/token/login" -Method Post `
        -ContentType 'application/x-www-form-urlencoded' -Body $form -Headers $loginHeaders `
        -SessionVariable payLensSession -SkipHttpErrorCheck -TimeoutSec 15
    $login = $loginResponse.Content | ConvertFrom-Json -AsHashtable
    $token = [string]$login.data
    if (-not $token -and $payLensSession) {
        foreach ($cookie in $payLensSession.Cookies.GetCookies([uri]$base)) {
            if ($cookie.Name -eq 'Accesstoken') { $token = $cookie.Value }
        }
    }
    if ([int]$loginResponse.StatusCode -ne 200 -or $login.code -ne 0 -or -not $token) {
        throw "Local demo login failed; HTTP=$([int]$loginResponse.StatusCode), code=$($login.code)."
    }
    $headers = @{ Accesstoken = $token; 'x-client-code' = 'admin' }

    $escapedTradeNo = [uri]::EscapeDataString($tradeNo)
    $escapedTaskId = [uri]::EscapeDataString($taskId)
    $timeline = Invoke-RestMethod -Uri "$base/paylens/api/trades/$escapedTradeNo/timeline" `
        -Headers $headers -WebSession $payLensSession -TimeoutSec 20
    $knownEvidence = @($timeline.data.evidence | ForEach-Object { $_.id })
    if ($timeline.code -ne 0 -or $knownEvidence.Count -lt 3) { throw 'The existing trade evidence snapshot is unavailable.' }

    $task = (Invoke-RestMethod -Uri "$base/paylens/api/trades/$escapedTradeNo/analyses/$escapedTaskId" `
        -Headers $headers -WebSession $payLensSession -TimeoutSec 20).data
    if ($task.status -eq 'FAILED') {
        $retryResponse = Invoke-WebRequest -Uri "$base/paylens/api/trades/$escapedTradeNo/analyses/$escapedTaskId/retry" `
            -Method Post -Headers $headers -WebSession $payLensSession -SkipHttpErrorCheck -TimeoutSec 20
        $retry = $retryResponse.Content | ConvertFrom-Json -AsHashtable
        if ([int]$retryResponse.StatusCode -ne 202 -or $retry.code -ne 0) {
            throw "Analysis retry was not accepted; HTTP=$([int]$retryResponse.StatusCode), code=$($retry.code)."
        }
        Write-Output "STEP retry=accepted taskId=$taskId"
        $task = $retry.data
    } elseif ($task.status -eq 'SUCCEEDED') {
        Write-Output "STEP existing-analysis=already-succeeded taskId=$taskId"
    } else {
        throw "Existing task is not retryable; status=$($task.status)."
    }

    for ($i = 0; $i -lt 55 -and $task.status -notin @('SUCCEEDED', 'FAILED', 'SKIPPED'); $i++) {
        Start-Sleep -Seconds 2
        $poll = Invoke-RestMethod -Uri "$base/paylens/api/trades/$escapedTradeNo/analyses/$escapedTaskId" `
            -Headers $headers -WebSession $payLensSession -TimeoutSec 20
        $task = $poll.data
    }
    Write-Output "STEP analysis-status=$($task.status) durationMs=$($task.durationMs) failureReason=$($task.failureReason)"
    if ($task.status -ne 'SUCCEEDED') { throw "Analysis did not succeed; status=$($task.status), failureReason=$($task.failureReason)." }

    $references = @($task.result.facts | ForEach-Object { $_.evidenceIds })
    $invalidReferences = @($references | Where-Object { $_ -notin $knownEvidence })
    if ($invalidReferences.Count -gt 0) { throw 'Analysis returned an evidence reference outside the submitted snapshot.' }
    Write-Output "STEP evidence-reference-validation=passed evidenceCount=$($knownEvidence.Count) invalidReferences=$($invalidReferences.Count)"

    $reportResponse = Invoke-WebRequest -Uri "$base/paylens/api/trades/$escapedTradeNo/analyses/$escapedTaskId/report" `
        -Headers $headers -WebSession $payLensSession -TimeoutSec 20
    $reportPath = Join-Path $PSScriptRoot 'paylens-last-run-report.md'
    Set-Content -LiteralPath $reportPath -Value $reportResponse.Content -Encoding utf8
    Write-Output "STEP report-exported chars=$($reportResponse.Content.Length)"

    $verified = Invoke-RestMethod -Uri "$base/paylens/api/trades/$escapedTradeNo/timeline" `
        -Headers $headers -WebSession $payLensSession -TimeoutSec 20
    Write-Output "STEP final-trade-verification localStatus=$($verified.data.localStatus) handleStatus=$($verified.data.handleStatus) events=$(@($verified.data.timeline).Count)"
}
catch {
    Write-Output "FLOW_FAILED type=$($_.Exception.GetType().Name) message=$($_.Exception.Message)"
    throw
}
finally {
    $password = $null
    $encodedPassword = $null
    $token = $null
    $cipher = $null
    if ($rsa) { $rsa.Dispose() }
}
