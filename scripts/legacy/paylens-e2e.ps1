$ErrorActionPreference = "Stop"
$base = "http://127.0.0.1:9999"
$step = "startup-check"
$token = $null
$password = $null
$rsa = $null

try {
    $health = Invoke-RestMethod -Uri "$base/actuator/health" -TimeoutSec 10
    if ($health.status -ne "UP") { throw "Health endpoint did not report UP." }
    Write-Output "STEP health=UP"

    $step = "login-context"
    $content = Invoke-RestMethod -Uri "$base/token/login-content" -Method Post `
        -ContentType "application/json" -Body '{"clientId":"admin"}' -TimeoutSec 10
    if (-not $content.data.passwordEncrypted) { throw "Login context still reports plaintext password transport." }
    Write-Output "STEP login-context=RSA-encrypted"

    $doc = Get-Content -LiteralPath (Join-Path $PSScriptRoot "../../_config/sql/README.md") -Raw
    $match = [regex]::Match($doc, "开发默认密码 \*\*(?<value>[^*]+)\*\*")
    if (-not $match.Success) { throw "Documented local development credential was not found." }
    $password = $match.Groups["value"].Value

    $step = "rsa-encrypt-password"
    $keyResponse = Invoke-RestMethod -Uri "$base/token/public-key" -TimeoutSec 10
    $rsa = [System.Security.Cryptography.RSA]::Create()
    $rsa.ImportFromPem([string]$keyResponse.data)
    $cipher = $rsa.Encrypt([System.Text.Encoding]::UTF8.GetBytes($password), [System.Security.Cryptography.RSAEncryptionPadding]::Pkcs1)
    $encodedPassword = [Convert]::ToBase64String($cipher)

    $step = "admin-login"
    $nonce = Invoke-RestMethod -Uri "$base/nonce/generate" -Method Post -TimeoutSec 10
    $loginHeaders = @{
        "x-nonce" = [string]$nonce.data.nonce
        "x-timestamp" = [string]$nonce.data.timestamp
    }
    $form = "account=bootx&password=$([uri]::EscapeDataString($encodedPassword))&loginType=password&client=admin"
    $loginResponse = Invoke-WebRequest -Uri "$base/token/login" -Method Post `
        -ContentType "application/x-www-form-urlencoded" -Body $form -Headers $loginHeaders `
        -SessionVariable payLensSession -SkipHttpErrorCheck -TimeoutSec 15
    $login = $loginResponse.Content | ConvertFrom-Json -AsHashtable
    $token = [string]$login.data
    if (-not $token -and $payLensSession) {
        foreach ($cookie in $payLensSession.Cookies.GetCookies([uri]$base)) {
            if ($cookie.Name -eq "Accesstoken") { $token = $cookie.Value }
        }
    }
    if ([int]$loginResponse.StatusCode -ne 200 -or $login.code -ne 0 -or -not $token) {
        throw "Admin login failed; HTTP=$([int]$loginResponse.StatusCode), code=$($login.code), message=$($login.msg)"
    }
    $headers = @{ "Accesstoken" = $token; "x-client-code" = "admin" }
    Write-Output "STEP admin-login=authenticated"

    $step = "workbench-and-permission-check"
    $page = Invoke-WebRequest -Uri "$base/paylens" -Headers $headers -WebSession $payLensSession -TimeoutSec 15
    if ($page.StatusCode -ne 200 -or -not $page.Content.Contains("PayLens")) { throw "Authenticated workbench page was not available." }
    $trades = Invoke-RestMethod -Uri "$base/paylens/api/trades" -Headers $headers -WebSession $payLensSession -TimeoutSec 15
    if ($trades.code -ne 0) { throw "Authorized trade-list API did not return success." }
    Write-Output "STEP workbench-and-permission-check=passed existingDemoTrades=$(@($trades.data).Count)"

    $step = "create-synthetic-late-success"
    $scenarioBody = @{ scenario = "LATE_SUCCESS" } | ConvertTo-Json -Compress
    $scenarioResponse = Invoke-WebRequest -Uri "$base/paylens/api/scenarios" -Method Post `
        -ContentType "application/json" -Body $scenarioBody -Headers $headers -WebSession $payLensSession `
        -SkipHttpErrorCheck -TimeoutSec 30
    $scenario = $scenarioResponse.Content | ConvertFrom-Json -AsHashtable
    if ([int]$scenarioResponse.StatusCode -ne 200 -or $scenario.code -ne 0 -or -not $scenario.data.tradeNo) {
        throw "Synthetic scenario creation failed; HTTP=$([int]$scenarioResponse.StatusCode), code=$($scenario.code), message=$($scenario.msg)"
    }
    $tradeNo = [uri]::EscapeDataString([string]$scenario.data.tradeNo)
    Write-Output "STEP scenario=created tradeNo=$($scenario.data.tradeNo) localStatus=$($scenario.data.localStatus)"

    $step = "evidence-timeline"
    $timelineResponse = Invoke-RestMethod -Uri "$base/paylens/api/trades/$tradeNo/timeline" -Headers $headers -WebSession $payLensSession -TimeoutSec 20
    $detail = $timelineResponse.data
    if ($timelineResponse.code -ne 0 -or -not $detail.abnormalId -or @($detail.evidence).Count -lt 3) {
        throw "Expected late-success abnormal order and evidence were not returned."
    }
    $knownEvidence = @($detail.evidence | ForEach-Object { $_.id })
    Write-Output "STEP evidence-timeline=ready abnormalId=$($detail.abnormalId) events=$(@($detail.timeline).Count) evidence=$($knownEvidence.Count)"

    $step = "submit-analysis"
    $analysisResponse = Invoke-WebRequest -Uri "$base/paylens/api/trades/$tradeNo/analyses" -Method Post `
        -Headers $headers -WebSession $payLensSession -SkipHttpErrorCheck -TimeoutSec 20
    $analysis = $analysisResponse.Content | ConvertFrom-Json -AsHashtable
    $task = $analysis.data
    if ([int]$analysisResponse.StatusCode -ne 202 -or $analysis.code -ne 0 -or -not $task.id) {
        throw "Analysis was not accepted; HTTP=$([int]$analysisResponse.StatusCode), code=$($analysis.code), message=$($analysis.msg)"
    }
    $taskId = [uri]::EscapeDataString([string]$task.id)
    Write-Output "STEP analysis=accepted taskId=$($task.id)"

    $step = "poll-analysis"
    for ($i = 0; $i -lt 30 -and $task.status -notin @("SUCCEEDED", "FAILED", "SKIPPED"); $i++) {
        Start-Sleep -Seconds 2
        $poll = Invoke-RestMethod -Uri "$base/paylens/api/trades/$tradeNo/analyses/$taskId" -Headers $headers -WebSession $payLensSession -TimeoutSec 20
        $task = $poll.data
    }
    Write-Output "STEP analysis-status=$($task.status) durationMs=$($task.durationMs) failureReason=$($task.failureReason)"
    if ($task.status -eq "SUCCEEDED") {
        $references = @($task.result.facts | ForEach-Object { $_.evidenceIds })
        $invalidReferences = @($references | Where-Object { $_ -notin $knownEvidence })
        Write-Output "DIAGNOSIS summary=$($task.result.summary)"
        foreach ($cause in $task.result.possibleCauses) { Write-Output "CAUSE=$($cause.hypothesis)" }
        foreach ($stepText in $task.result.checkSteps) { Write-Output "CHECK=$stepText" }
        Write-Output "CITATIONS=$(@($task.result.knowledgeCitations) -join ",") invalidEvidenceReferences=$($invalidReferences.Count)"
        if ($invalidReferences.Count -gt 0) { throw "Analysis returned an evidence reference outside the submitted snapshot." }

        $step = "export-report"
        $reportResponse = Invoke-WebRequest -Uri "$base/paylens/api/trades/$tradeNo/analyses/$taskId/report" `
            -Headers $headers -WebSession $payLensSession -TimeoutSec 20
        $reportPath = Join-Path $PSScriptRoot "paylens-last-run-report.md"
        Set-Content -LiteralPath $reportPath -Value $reportResponse.Content -Encoding utf8
        Write-Output "STEP report-exported path=$reportPath chars=$($reportResponse.Content.Length)"
    }

    $step = "human-confirm-synthetic-trade"
    $actionBody = @{ remark = "PayLens 本地演示：基于模拟通道成功查单证据完成人工核实。" } | ConvertTo-Json -Compress
    $actionResponse = Invoke-WebRequest -Uri "$base/paylens/api/anomalies/$($detail.abnormalId)/confirm-success" `
        -Method Post -ContentType "application/json" -Body $actionBody -Headers $headers -WebSession $payLensSession `
        -SkipHttpErrorCheck -TimeoutSec 30
    if ([int]$actionResponse.StatusCode -ne 200) { throw "Synthetic manual confirmation failed; HTTP=$([int]$actionResponse.StatusCode)." }
    $verified = Invoke-RestMethod -Uri "$base/paylens/api/trades/$tradeNo/timeline" -Headers $headers -WebSession $payLensSession -TimeoutSec 20
    Write-Output "STEP post-action-verification localStatus=$($verified.data.localStatus) handleStatus=$($verified.data.handleStatus) events=$(@($verified.data.timeline).Count)"
}
catch {
    Write-Output "FLOW_FAILED step=$step type=$($_.Exception.GetType().Name) message=$($_.Exception.Message)"
    throw
}
finally {
    $password = $null
    $encodedPassword = $null
    $token = $null
    $cipher = $null
    if ($rsa) { $rsa.Dispose() }
}
