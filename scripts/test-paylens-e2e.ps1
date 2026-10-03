param(
    [string]$BaseUrl = 'http://127.0.0.1:9999',
    [string]$Scenario = 'LATE_SUCCESS',
    [string]$ResultDirectory = '',
    [switch]$ConcurrentDisposition,
    [switch]$ConcurrentSyncCallback
)

$ErrorActionPreference = 'Stop'
$step = 'startup-check'
$password = $null
$token = $null
$cipher = $null
$rsa = $null
$business = [ordered]@{ status = 'NOT_RUN'; tradeNo = $null; abnormalId = $null; finalLocalStatus = $null; finalHandleStatus = $null; dispositionRace = @(); syncCallbackRace = @(); notificationCount = 0 }
$accessControl = [ordered]@{ adminRead = 'NOT_RUN'; adminManage = 'NOT_RUN'; anonymousReadHttp = $null; merchantReadHttp = $null; merchantManageHttp = $null; permissionMatrix = 'NOT_RUN' }
$diagnosis = [ordered]@{ status = 'NOT_RUN'; taskId = $null; failureCode = $null; invalidEvidenceReferences = @(); invalidKnowledgeReferences = @(); result = $null }
$diagnosisFailure = $null
$runId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
if ([string]::IsNullOrWhiteSpace($ResultDirectory)) {
    $ResultDirectory = Join-Path $PSScriptRoot "../daxpay-start/target/paylens-e2e-results/$runId"
}
New-Item -ItemType Directory -Force -Path $ResultDirectory | Out-Null
$summaryPath = Join-Path $ResultDirectory 'summary.json'

function Save-RunSummary {
    param([string]$DiagnosisStatus)
    $summary = [ordered]@{
        runId = $runId
        baseUrl = $BaseUrl
        scenario = $Scenario
        completedAt = [DateTimeOffset]::Now.ToString('o')
        business = $business
        accessControl = $accessControl
        diagnosis = $diagnosis
        diagnosisFailure = $diagnosisFailure
    }
    $summary | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    Write-Output "BUSINESS_RESULT=$($business.status) tradeNo=$($business.tradeNo) localStatus=$($business.finalLocalStatus) handleStatus=$($business.finalHandleStatus)"
    Write-Output "DIAGNOSIS_RESULT=$DiagnosisStatus taskId=$($diagnosis.taskId) failureCode=$($diagnosis.failureCode)"
    Write-Output "RESULT_FILE=$summaryPath"
}

function Invoke-JsonRequest {
    param(
        [string]$Uri,
        [string]$Method = 'GET',
        [string]$Body = '',
        [int]$ExpectedStatus = 200,
        [hashtable]$RequestHeaders = $headers,
        [Microsoft.PowerShell.Commands.WebRequestSession]$Session = $payLensSession,
        [int]$TimeoutSec = 20
    )
    $params = @{
        Uri = $Uri
        Method = $Method
        Headers = $RequestHeaders
        WebSession = $Session
        SkipHttpErrorCheck = $true
        TimeoutSec = $TimeoutSec
    }
    if ($Body) {
        $params.ContentType = 'application/json'
        $params.Body = $Body
    }
    $response = Invoke-WebRequest @params
    try {
        $json = $response.Content | ConvertFrom-Json -AsHashtable
    } catch {
        $bodyPreview = [string]$response.Content
        if ($bodyPreview.Length -gt 500) { $bodyPreview = $bodyPreview.Substring(0, 500) }
        throw "Non-JSON response; HTTP=$([int]$response.StatusCode), contentType=$($response.Headers['Content-Type']), body=$bodyPreview"
    }
    if ([int]$response.StatusCode -ne $ExpectedStatus) {
        throw "HTTP $([int]$response.StatusCode), expected $ExpectedStatus, API code=$($json.code), message=$($json.msg)"
    }
    if ($null -ne $json.code -and [int]$json.code -ne 0) {
        $bodyPreview = [string]$response.Content
        if ($bodyPreview.Length -gt 1000) { $bodyPreview = $bodyPreview.Substring(0, 1000) }
        throw "API business code $($json.code): $($json.msg); HTTP=$([int]$response.StatusCode); body=$bodyPreview"
    }
    return [pscustomobject]@{ Response = $response; Json = $json }
}

function Get-AdminPermissionTestToken {
    param([string]$AccountName, [string]$LoginPassword, [System.Security.Cryptography.RSA]$RsaKey)
    $context = Invoke-RestMethod -Uri "$BaseUrl/token/login-content" -Method Post `
        -ContentType 'application/json' -Body '{"clientId":"admin"}' -TimeoutSec 10
    if (-not $context.data.passwordEncrypted) { throw 'Permission-matrix admin login is not using RSA password transport.' }
    $nonce = Invoke-RestMethod -Uri "$BaseUrl/nonce/generate" -Method Post -TimeoutSec 10
    $cipherBytes = $RsaKey.Encrypt([Text.Encoding]::UTF8.GetBytes($LoginPassword), [Security.Cryptography.RSAEncryptionPadding]::Pkcs1)
    $encodedPassword = [Convert]::ToBase64String($cipherBytes)
    $loginHeaders = @{ 'x-nonce' = [string]$nonce.data.nonce; 'x-timestamp' = [string]$nonce.data.timestamp }
    $form = "account=$([uri]::EscapeDataString($AccountName))&password=$([uri]::EscapeDataString($encodedPassword))&loginType=password&client=admin"
    $loginSession = [Microsoft.PowerShell.Commands.WebRequestSession]::new()
    $response = Invoke-WebRequest -Uri "$BaseUrl/token/login" -Method Post -ContentType 'application/x-www-form-urlencoded' `
        -Body $form -Headers $loginHeaders -WebSession $loginSession -SkipHttpErrorCheck -TimeoutSec 15
    $result = $response.Content | ConvertFrom-Json -AsHashtable
    $accessToken = [string]$result.data
    if (-not $accessToken) {
        foreach ($cookie in $loginSession.Cookies.GetCookies([uri]$BaseUrl)) {
            if ($cookie.Name -eq 'Accesstoken') { $accessToken = $cookie.Value }
        }
    }
    if ([int]$response.StatusCode -ne 200 -or $result.code -ne 0 -or -not $accessToken) {
        throw "Permission-matrix account '$AccountName' could not log in; HTTP=$([int]$response.StatusCode), code=$($result.code)."
    }
    return $accessToken
}

function Invoke-PermissionProbe {
    param([string]$Uri, [string]$Method, [string]$AccessToken, [string]$Body = '')
    $params = @{
        Uri = $Uri
        Method = $Method
        Headers = @{ Accesstoken = $AccessToken }
        SkipHttpErrorCheck = $true
        TimeoutSec = 15
    }
    if ($Body) { $params.ContentType = 'application/json'; $params.Body = $Body }
    $response = Invoke-WebRequest @params
    $result = $null
    try { $result = $response.Content | ConvertFrom-Json -AsHashtable } catch { }
    $apiCode = if ($null -ne $result -and $null -ne $result.code) { [int]$result.code } else { $null }
    return [pscustomobject]@{
        allowed = [int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 300 -and $apiCode -eq 0
        httpStatus = [int]$response.StatusCode
        apiCode = $apiCode
    }
}

try {
    $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -TimeoutSec 10
    if ($health.status -ne 'UP') { throw 'Actuator health did not report UP.' }
    Write-Output 'STEP health=UP'

    $step = 'login-context'
    $loginContext = Invoke-RestMethod -Uri "$BaseUrl/token/login-content" -Method Post `
        -ContentType 'application/json' -Body '{"clientId":"admin"}' -TimeoutSec 10
    if (-not $loginContext.data.passwordEncrypted) { throw 'Admin login is not using RSA password transport.' }

    $account = if ($env:PAYLENS_DEMO_ACCOUNT) { $env:PAYLENS_DEMO_ACCOUNT } else { 'bootx' }
    $password = $env:PAYLENS_DEMO_PASSWORD
    if ([string]::IsNullOrWhiteSpace($password)) {
        $docPath = Join-Path $PSScriptRoot '../_config/sql/README.md'
        $doc = Get-Content -LiteralPath $docPath -Raw
        $match = [regex]::Match($doc, '开发默认密码 \*\*(?<value>[^*]+)\*\*')
        if (-not $match.Success) { throw 'Set PAYLENS_DEMO_PASSWORD or document the local demo credential.' }
        $password = $match.Groups['value'].Value
    }
    $keyResponse = Invoke-RestMethod -Uri "$BaseUrl/token/public-key" -TimeoutSec 10
    $rsa = [System.Security.Cryptography.RSA]::Create()
    $rsa.ImportFromPem([string]$keyResponse.data)
    $cipher = $rsa.Encrypt([Text.Encoding]::UTF8.GetBytes($password), [Security.Cryptography.RSAEncryptionPadding]::Pkcs1)
    $encodedPassword = [Convert]::ToBase64String($cipher)

    $nonce = Invoke-RestMethod -Uri "$BaseUrl/nonce/generate" -Method Post -TimeoutSec 10
    $loginHeaders = @{ 'x-nonce' = [string]$nonce.data.nonce; 'x-timestamp' = [string]$nonce.data.timestamp }
    $form = "account=$([uri]::EscapeDataString($account))&password=$([uri]::EscapeDataString($encodedPassword))&loginType=password&client=admin"
    $loginResponse = Invoke-WebRequest -Uri "$BaseUrl/token/login" -Method Post `
        -ContentType 'application/x-www-form-urlencoded' -Body $form -Headers $loginHeaders `
        -SessionVariable payLensSession -SkipHttpErrorCheck -TimeoutSec 15
    $login = $loginResponse.Content | ConvertFrom-Json -AsHashtable
    $token = [string]$login.data
    if (-not $token -and $payLensSession) {
        foreach ($cookie in $payLensSession.Cookies.GetCookies([uri]$BaseUrl)) {
            if ($cookie.Name -eq 'Accesstoken') { $token = $cookie.Value }
        }
    }
    if ([int]$loginResponse.StatusCode -ne 200 -or $login.code -ne 0 -or -not $token) {
        throw "Admin login failed; HTTP=$([int]$loginResponse.StatusCode), code=$($login.code)."
    }
    $headers = @{ Accesstoken = $token }
    Write-Output 'STEP admin-login=authenticated'

    $step = 'authenticated-workbench-and-anonymous-denial'
    $page = Invoke-WebRequest -Uri "$BaseUrl/paylens" -Headers $headers -WebSession $payLensSession -TimeoutSec 15
    if ($page.StatusCode -ne 200 -or -not $page.Content.Contains('PayLens')) { throw 'Authenticated PayLens workbench page is unavailable.' }
    $authorized = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/trades" -RequestHeaders $headers -Session $payLensSession
    $accessControl.adminRead = 'ALLOWED'
    $anonymous = Invoke-WebRequest -Uri "$BaseUrl/paylens/api/trades" -SkipHttpErrorCheck -TimeoutSec 10
    if ([int]$anonymous.StatusCode -notin @(401, 403)) { throw "Anonymous trade API was not rejected; HTTP=$([int]$anonymous.StatusCode)." }
    $accessControl.anonymousReadHttp = [int]$anonymous.StatusCode
    if ($env:PAYLENS_TEST_NON_ADMIN -eq '1') {
        $merchantAccount = if ($env:PAYLENS_NONADMIN_ACCOUNT) { $env:PAYLENS_NONADMIN_ACCOUNT } else { 'csqysh' }
        $merchantContext = Invoke-RestMethod -Uri "$BaseUrl/token/login-content" -Method Post `
            -ContentType 'application/json' -Body '{"clientId":"merchant"}' -TimeoutSec 10
        if (-not $merchantContext.data.passwordEncrypted) { throw 'Merchant login is not using RSA password transport.' }
        $merchantNonce = Invoke-RestMethod -Uri "$BaseUrl/nonce/generate" -Method Post -TimeoutSec 10
        $merchantCipher = $rsa.Encrypt([Text.Encoding]::UTF8.GetBytes($password), [Security.Cryptography.RSAEncryptionPadding]::Pkcs1)
        $merchantEncodedPassword = [Convert]::ToBase64String($merchantCipher)
        $merchantHeaders = @{ 'x-nonce' = [string]$merchantNonce.data.nonce; 'x-timestamp' = [string]$merchantNonce.data.timestamp }
        $merchantForm = "account=$([uri]::EscapeDataString($merchantAccount))&password=$([uri]::EscapeDataString($merchantEncodedPassword))&loginType=password&client=merchant"
        $merchantLoginResponse = Invoke-WebRequest -Uri "$BaseUrl/token/login" -Method Post `
            -ContentType 'application/x-www-form-urlencoded' -Body $merchantForm -Headers $merchantHeaders `
            -SessionVariable merchantSession -SkipHttpErrorCheck -TimeoutSec 15
        $merchantLogin = $merchantLoginResponse.Content | ConvertFrom-Json -AsHashtable
        $merchantToken = [string]$merchantLogin.data
        if (-not $merchantToken -and $merchantSession) {
            foreach ($cookie in $merchantSession.Cookies.GetCookies([uri]$BaseUrl)) {
                if ($cookie.Name -eq 'Accesstoken') { $merchantToken = $cookie.Value }
            }
        }
        if ([int]$merchantLoginResponse.StatusCode -ne 200 -or $merchantLogin.code -ne 0 -or -not $merchantToken) {
            throw "Seeded merchant client login failed; HTTP=$([int]$merchantLoginResponse.StatusCode), code=$($merchantLogin.code)."
        }
        $merchantHeaders = @{ Accesstoken = $merchantToken }
        $merchantRead = Invoke-WebRequest -Uri "$BaseUrl/paylens/api/trades" -Headers $merchantHeaders `
            -SkipHttpErrorCheck -TimeoutSec 10
        $merchantManage = Invoke-WebRequest -Uri "$BaseUrl/paylens/api/scenarios" -Method Post `
            -ContentType 'application/json' -Body '{"scenario":"NORMAL_SUCCESS"}' -Headers $merchantHeaders `
            -SkipHttpErrorCheck -TimeoutSec 10
        $accessControl.merchantReadHttp = [int]$merchantRead.StatusCode
        $accessControl.merchantManageHttp = [int]$merchantManage.StatusCode
        if ($accessControl.merchantReadHttp -notin @(401, 403) -or $accessControl.merchantManageHttp -notin @(401, 403)) {
            throw "Non-admin client was not denied on both read and manage routes; read=$($accessControl.merchantReadHttp), manage=$($accessControl.merchantManageHttp)."
        }
        $merchantCipher = $null
        $merchantEncodedPassword = $null
        $merchantToken = $null
    }
    if ($env:PAYLENS_TEST_PERMISSION_MATRIX -eq '1') {
        $noPermissionToken = Get-AdminPermissionTestToken -AccountName 'paylens_no_permission' -LoginPassword $password -RsaKey $rsa
        $noPermissionRead = Invoke-PermissionProbe -Uri "$BaseUrl/paylens/api/trades" -Method GET -AccessToken $noPermissionToken
        $noPermissionManage = Invoke-PermissionProbe -Uri "$BaseUrl/paylens/api/scenarios" -Method POST `
            -AccessToken $noPermissionToken -Body '{"scenario":"NORMAL_SUCCESS"}'
        $viewOnlyToken = Get-AdminPermissionTestToken -AccountName 'paylens_view_only' -LoginPassword $password -RsaKey $rsa
        $viewOnlyRead = Invoke-PermissionProbe -Uri "$BaseUrl/paylens/api/trades" -Method GET -AccessToken $viewOnlyToken
        $viewOnlyManage = Invoke-PermissionProbe -Uri "$BaseUrl/paylens/api/scenarios" -Method POST `
            -AccessToken $viewOnlyToken -Body '{"scenario":"NORMAL_SUCCESS"}'
        $accessControl.permissionMatrix = [ordered]@{
            noPermissionRead = if ($noPermissionRead.allowed) { 'ALLOWED' } else { 'DENIED' }
            noPermissionManage = if ($noPermissionManage.allowed) { 'ALLOWED' } else { 'DENIED' }
            viewOnlyRead = if ($viewOnlyRead.allowed) { 'ALLOWED' } else { 'DENIED' }
            viewOnlyManage = if ($viewOnlyManage.allowed) { 'ALLOWED' } else { 'DENIED' }
            responses = @{
                noPermissionRead = $noPermissionRead
                noPermissionManage = $noPermissionManage
                viewOnlyRead = $viewOnlyRead
                viewOnlyManage = $viewOnlyManage
            }
        }
        if ($noPermissionRead.allowed -or $noPermissionManage.allowed -or
                -not $viewOnlyRead.allowed -or $viewOnlyManage.allowed) {
            throw 'PayLens permission matrix failed for no-permission or view-only admin accounts.'
        }
        $noPermissionToken = $null
        $viewOnlyToken = $null
    }
    Write-Output "STEP access-control=passed adminRead=$($accessControl.adminRead) anonymousHttp=$($accessControl.anonymousReadHttp) merchantReadHttp=$($accessControl.merchantReadHttp) merchantManageHttp=$($accessControl.merchantManageHttp) existingDemoTrades=$(@($authorized.Json.data).Count)"

    $step = 'create-synthetic-scenario'
    $scenarioBody = @{ scenario = $Scenario } | ConvertTo-Json -Compress
    $scenarioCall = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/scenarios" -Method Post `
        -Body $scenarioBody -ExpectedStatus 200 -RequestHeaders $headers -Session $payLensSession -TimeoutSec 30
    Set-Content -LiteralPath (Join-Path $ResultDirectory 'scenario-response.json') -Value $scenarioCall.Response.Content -Encoding utf8
    $scenarioResult = $scenarioCall.Json.data
    $business.tradeNo = [string]$scenarioResult.tradeNo
    $business.status = 'SCENARIO_CREATED'
    if ($Scenario -eq 'DUPLICATE_CALLBACK') {
        $expectedNotifications = 2
        if ($env:PAYLENS_DEMO_DUPLICATE_CALLBACK_COUNT) {
            $expectedNotifications = [int]$env:PAYLENS_DEMO_DUPLICATE_CALLBACK_COUNT
        }
        $business.notificationCount = [int]$scenarioResult.notificationCount
        if ($business.notificationCount -ne $expectedNotifications) {
            throw "Duplicate callback scenario sent $($business.notificationCount) notifications; expected $expectedNotifications."
        }
    }
    $tradeNo = [uri]::EscapeDataString($business.tradeNo)
    Write-Output "STEP scenario=created tradeNo=$($business.tradeNo) localStatus=$($scenarioResult.localStatus) responseKeys=$(@($scenarioResult.Keys) -join ',')"

    $step = 'evidence-timeline'
    $timelineCall = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/trades/$tradeNo/timeline" `
        -RequestHeaders $headers -Session $payLensSession
    $detail = $timelineCall.Json.data
    $business.abnormalId = $detail.abnormalId
    $scenarioExpectations = @{
        NORMAL_SUCCESS = @{ localStatus = 'success'; channelStatus = 'SUCCESS'; abnormalType = 'NONE'; handleStatus = 'NOT_ABNORMAL' }
        CALLBACK_LOST = @{ localStatus = 'processing'; channelStatus = 'SUCCESS'; abnormalType = 'NONE'; handleStatus = 'NOT_ABNORMAL' }
        LATE_SUCCESS = @{ localStatus = 'close'; channelStatus = 'SUCCESS'; abnormalType = 'close_paid'; handleStatus = 'pending' }
        DUPLICATE_CALLBACK = @{ localStatus = 'success'; channelStatus = 'SUCCESS'; abnormalType = 'NONE'; handleStatus = 'NOT_ABNORMAL' }
        QUERY_TIMEOUT = @{ localStatus = 'processing'; channelStatus = 'UNKNOWN'; abnormalType = 'NONE'; handleStatus = 'NOT_ABNORMAL' }
    }
    $expectedScenario = $scenarioExpectations[$Scenario.ToUpperInvariant()]
    if (-not $expectedScenario) { throw "Unsupported scenario '$Scenario'." }
    if ($detail.abnormalType -ne $expectedScenario.abnormalType) {
        throw "Scenario $Scenario expected abnormalType=$($expectedScenario.abnormalType), got $($detail.abnormalType)."
    }
    if ([string]$detail.localStatus -ne $expectedScenario.localStatus) {
        throw "Scenario $Scenario expected localStatus=$($expectedScenario.localStatus), got $($detail.localStatus)."
    }
    if ([string]$detail.channelStatus -ne $expectedScenario.channelStatus) {
        throw "Scenario $Scenario expected channelStatus=$($expectedScenario.channelStatus), got $($detail.channelStatus)."
    }
    if ([string]$detail.handleStatus -ne $expectedScenario.handleStatus) {
        throw "Scenario $Scenario expected handleStatus=$($expectedScenario.handleStatus), got $($detail.handleStatus)."
    }
    if ($Scenario -eq 'LATE_SUCCESS' -and -not $detail.abnormalId) { throw 'Late-success scenario did not create its abnormal order.' }
    if ($Scenario -ne 'LATE_SUCCESS' -and $detail.abnormalId) { throw "Scenario $Scenario unexpectedly created abnormal order $($detail.abnormalId)." }
    $knownEvidence = @($detail.evidence | ForEach-Object { $_.id })
    if ($knownEvidence.Count -lt 3) { throw 'The transaction evidence snapshot is incomplete.' }
    Write-Output "STEP evidence=ready abnormalType=$($detail.abnormalType) events=$(@($detail.timeline).Count) items=$($knownEvidence.Count)"

    $step = 'submit-diagnostic'
    $analysisSubmit = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/trades/$tradeNo/analyses" -Method Post `
        -Body '{}' -ExpectedStatus 202 -RequestHeaders $headers -Session $payLensSession
    $accessControl.adminManage = 'ALLOWED'
    $task = $analysisSubmit.Json.data
    $diagnosis.taskId = [string]$task.id
    if (-not $diagnosis.taskId) { throw 'Analysis was accepted without a task ID.' }
    $taskId = [uri]::EscapeDataString($diagnosis.taskId)
    Write-Output "STEP diagnosis=accepted taskId=$($diagnosis.taskId)"

    $step = 'poll-diagnostic'
    for ($i = 0; $i -lt 60 -and $task.status -notin @('SUCCEEDED', 'FAILED'); $i++) {
        Start-Sleep -Seconds 2
        $poll = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/trades/$tradeNo/analyses/$taskId" `
            -RequestHeaders $headers -Session $payLensSession
        $task = $poll.Json.data
    }
    $diagnosis.status = [string]$task.status
    $diagnosis.failureCode = [string]$task.failureReason
    if ($task.status -eq 'SUCCEEDED') {
        $diagnosis.result = $task.result
        $factReferences = @($task.result.facts | ForEach-Object { $_.evidenceIds } | ForEach-Object { $_ })
        $causeReferences = @($task.result.possibleCauses | ForEach-Object { $_.evidenceIds } | ForEach-Object { $_ })
        $diagnosis.invalidEvidenceReferences = @($factReferences + $causeReferences | Where-Object { $_ -notin $knownEvidence })
        $knowledgeIds = @($task.execution.retrievedKnowledge | ForEach-Object { $_.id })
        $diagnosis.invalidKnowledgeReferences = @($task.result.knowledgeCitations | Where-Object { $_ -notin $knowledgeIds })
        if ($diagnosis.invalidEvidenceReferences.Count -gt 0) { $diagnosisFailure = 'Model cited evidence outside the immutable snapshot.' }
        if ($diagnosis.invalidKnowledgeReferences.Count -gt 0) { $diagnosisFailure = 'Model cited knowledge outside the saved retrieval set.' }

        $history = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/trades/$tradeNo/analyses?page=1&pageSize=20" `
            -RequestHeaders $headers -Session $payLensSession
        if (@($history.Json.data.items | ForEach-Object { $_.id }) -notcontains $diagnosis.taskId) {
            $diagnosisFailure = 'Successful analysis is missing from paged history.'
        }
        $report = Invoke-WebRequest -Uri "$BaseUrl/paylens/api/trades/$tradeNo/analyses/$taskId/report" `
            -Headers $headers -WebSession $payLensSession -TimeoutSec 20
        if ($report.StatusCode -ne 200 -or -not $report.Content.Contains('证据快照时间')) {
            $diagnosisFailure = 'Markdown report omitted the saved evidence and execution version details.'
        }
        Set-Content -LiteralPath (Join-Path $ResultDirectory 'diagnostic-report.md') -Value $report.Content -Encoding utf8
        $diagnosis.status = if ($diagnosisFailure) { 'FAILED_VALIDATION' } else { 'SUCCEEDED' }
    } elseif ($task.status -eq 'FAILED') {
        $diagnosisFailure = "Diagnostic task failed: $($task.failureReason)"
    } else {
        $diagnosisFailure = "Diagnostic task did not finish before timeout; status=$($task.status)."
        $diagnosis.status = 'TIMEOUT'
    }
    $task | ConvertTo-Json -Depth 24 | Set-Content -LiteralPath (Join-Path $ResultDirectory 'diagnostic-task.json') -Encoding utf8
    Write-Output "STEP diagnosis-status=$($diagnosis.status) failureCode=$($diagnosis.failureCode)"

    if ($Scenario -eq 'LATE_SUCCESS' -and $ConcurrentSyncCallback) {
        $step = 'concurrent-sync-vs-callback'
        $raceActions = @(
            @{ action = 'sync'; uri = "$BaseUrl/paylens/api/trades/$tradeNo/sync" },
            @{ action = 'callback'; uri = "$BaseUrl/paylens/api/scenarios/$tradeNo/replay-callback" }
        )
        $http = [System.Net.Http.HttpClient]::new()
        $http.Timeout = [TimeSpan]::FromSeconds(35)
        $http.DefaultRequestHeaders.Add('Accesstoken', $token)
        $pendingActions = @()
        try {
            foreach ($action in $raceActions) {
                $content = [System.Net.Http.StringContent]::new('{}', [Text.Encoding]::UTF8, 'application/json')
                $pendingActions += ,([pscustomobject]@{ action = $action.action; task = $http.PostAsync($action.uri, $content) })
            }
            $pendingTasks = [System.Threading.Tasks.Task[]]@($pendingActions | ForEach-Object { $_.task })
            [System.Threading.Tasks.Task]::WaitAll($pendingTasks)
            foreach ($pending in $pendingActions) {
                $response = $pending.task.Result
                $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $responseJson = $null
                try { $responseJson = $bodyText | ConvertFrom-Json -AsHashtable } catch { }
                $apiCode = if ($null -ne $responseJson -and $null -ne $responseJson.code) { [int]$responseJson.code } else { $null }
                $business.syncCallbackRace += [pscustomobject]@{
                    action = $pending.action
                    httpStatus = [int]$response.StatusCode
                    apiCode = $apiCode
                    succeeded = $response.IsSuccessStatusCode -and $null -ne $apiCode -and $apiCode -eq 0
                }
            }
        } finally {
            foreach ($pending in $pendingActions) {
                if ($pending.task.Status -eq [System.Threading.Tasks.TaskStatus]::RanToCompletion) { $pending.task.Result.Dispose() }
            }
            $http.Dispose()
        }
        if (@($business.syncCallbackRace).Count -ne 2 -or @($business.syncCallbackRace | Where-Object { $_.succeeded }).Count -eq 0) {
            throw 'Concurrent sync/callback test did not receive a successful business response.'
        }
        Write-Output "STEP sync-callback-race=completed sync=$((@($business.syncCallbackRace | Where-Object { $_.action -eq 'sync' })[0]).succeeded) callback=$((@($business.syncCallbackRace | Where-Object { $_.action -eq 'callback' })[0]).succeeded)"
    } elseif ($Scenario -eq 'LATE_SUCCESS' -and $ConcurrentDisposition) {
        $step = 'concurrent-confirm-vs-ignore'
        $confirmBody = @{ remark = 'PayLens isolated concurrency test: confirm after fresh channel query.' } | ConvertTo-Json -Compress
        $ignoreBody = @{ remark = 'PayLens isolated concurrency test: ignore contender.' } | ConvertTo-Json -Compress
        $actions = @(
            @{ action = 'confirm'; uri = "$BaseUrl/paylens/api/anomalies/$($detail.abnormalId)/confirm-success"; body = $confirmBody },
            @{ action = 'ignore'; uri = "$BaseUrl/paylens/api/anomalies/$($detail.abnormalId)/ignore"; body = $ignoreBody }
        )
        $http = [System.Net.Http.HttpClient]::new()
        $http.Timeout = [TimeSpan]::FromSeconds(35)
        $http.DefaultRequestHeaders.Add('Accesstoken', $token)
        try {
            $pendingActions = @()
            foreach ($action in $actions) {
                $content = [System.Net.Http.StringContent]::new($action.body, [Text.Encoding]::UTF8, 'application/json')
                $pendingActions += ,([pscustomobject]@{ action = $action.action; task = $http.PostAsync($action.uri, $content) })
            }
            $pendingTasks = [System.Threading.Tasks.Task[]]@($pendingActions | ForEach-Object { $_.task })
            [System.Threading.Tasks.Task]::WaitAll($pendingTasks)
            foreach ($pending in $pendingActions) {
                $response = $pending.task.Result
                $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                $responseJson = $null
                try { $responseJson = $bodyText | ConvertFrom-Json -AsHashtable } catch { }
                $apiCode = if ($null -ne $responseJson -and $null -ne $responseJson.code) { [int]$responseJson.code } else { $null }
                $business.dispositionRace += [pscustomobject]@{
                    action = $pending.action
                    httpStatus = [int]$response.StatusCode
                    apiCode = $apiCode
                    succeeded = $response.IsSuccessStatusCode -and $null -ne $apiCode -and $apiCode -eq 0
                }
            }
        } finally {
            foreach ($pending in $pendingActions) {
                if ($pending.task.Status -eq [System.Threading.Tasks.TaskStatus]::RanToCompletion) { $pending.task.Result.Dispose() }
            }
            $http.Dispose()
        }
        $winners = @($business.dispositionRace | Where-Object { $_.succeeded })
        if ($winners.Count -ne 1) {
            throw "Concurrent confirm/ignore expected exactly one successful action, got $($winners.Count)."
        }
        Write-Output "STEP disposition-race=verified winner=$($winners[0].action) loserHttp=$((@($business.dispositionRace | Where-Object { -not $_.succeeded })[0]).httpStatus)"
    } elseif ($Scenario -eq 'LATE_SUCCESS' -and -not $ConcurrentSyncCallback) {
        $step = 'business-confirmation-after-fresh-channel-query'
        $actionBody = @{ remark = 'PayLens 本地演示：基于本次模拟通道查单证据完成人工核实。' } | ConvertTo-Json -Compress
        Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/anomalies/$($detail.abnormalId)/confirm-success" `
            -Method Post -Body $actionBody -ExpectedStatus 200 -RequestHeaders $headers -Session $payLensSession -TimeoutSec 30 | Out-Null
    } elseif ($Scenario -eq 'CALLBACK_LOST') {
        $step = 'business-callback-replay'
        Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/scenarios/$tradeNo/replay-callback" -Method Post `
            -ExpectedStatus 200 -RequestHeaders $headers -Session $payLensSession -TimeoutSec 30 | Out-Null
    }
    $verified = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/trades/$tradeNo/timeline" `
        -RequestHeaders $headers -Session $payLensSession
    $business.finalLocalStatus = [string]$verified.Json.data.localStatus
    $business.finalHandleStatus = [string]$verified.Json.data.handleStatus
    if ($Scenario -eq 'LATE_SUCCESS' -and $ConcurrentSyncCallback) {
        $abnormalRows = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/anomalies" -RequestHeaders $headers -Session $payLensSession
        $matchingAbnormals = @($abnormalRows.Json.data | Where-Object { $_.tradeNo -eq $business.tradeNo })
        if ($verified.Json.data.localStatus -ne 'close' -or $verified.Json.data.handleStatus -ne 'pending' -or
            $verified.Json.data.abnormalType -ne 'close_paid' -or $matchingAbnormals.Count -ne 1) {
            throw "Sync/callback race produced inconsistent state or duplicate abnormal rows; local=$($verified.Json.data.localStatus), handle=$($verified.Json.data.handleStatus), matchingAbnormals=$($matchingAbnormals.Count)."
        }
        Write-Output "STEP sync-callback-state=consistent localStatus=$($verified.Json.data.localStatus) handleStatus=$($verified.Json.data.handleStatus) abnormalRows=$($matchingAbnormals.Count)"
    } elseif ($Scenario -eq 'LATE_SUCCESS' -and $ConcurrentDisposition) {
        $winner = @($business.dispositionRace | Where-Object { $_.succeeded })[0].action
        if ($winner -eq 'confirm' -and ($business.finalLocalStatus -ne 'success' -or $business.finalHandleStatus -ne 'confirmed')) {
            throw "Confirm won the race but state is local=$($business.finalLocalStatus), handle=$($business.finalHandleStatus)."
        }
        if ($winner -eq 'ignore' -and ($business.finalLocalStatus -ne 'close' -or $business.finalHandleStatus -ne 'ignored')) {
            throw "Ignore won the race but state is local=$($business.finalLocalStatus), handle=$($business.finalHandleStatus)."
        }
        Write-Output "STEP disposition-race-state=consistent winner=$winner localStatus=$($business.finalLocalStatus) handleStatus=$($business.finalHandleStatus)"
    } elseif ($Scenario -eq 'LATE_SUCCESS') {
        if ($business.finalLocalStatus -ne 'success' -or $business.finalHandleStatus -notin @('confirmed', 'CONFIRMED', 'confirm_success')) {
            throw "Synthetic business confirmation did not reach the expected terminal state; local=$($business.finalLocalStatus), handle=$($business.finalHandleStatus)."
        }
        Write-Output "STEP business-confirmation=verified localStatus=$($business.finalLocalStatus) handleStatus=$($business.finalHandleStatus)"
    } elseif ($Scenario -eq 'CALLBACK_LOST') {
        if ($business.finalLocalStatus -ne 'success' -or $business.finalHandleStatus -ne 'NOT_ABNORMAL') {
            throw "Callback replay did not restore the expected payment state; local=$($business.finalLocalStatus), handle=$($business.finalHandleStatus)."
        }
        Write-Output "STEP callback-replay=verified localStatus=$($business.finalLocalStatus) handleStatus=$($business.finalHandleStatus)"
    } else {
        if ($business.finalLocalStatus -ne $expectedScenario.localStatus -or $business.finalHandleStatus -ne $expectedScenario.handleStatus) {
            throw "Scenario $Scenario changed state unexpectedly; local=$($business.finalLocalStatus), handle=$($business.finalHandleStatus)."
        }
        Write-Output "STEP scenario-state=stable localStatus=$($business.finalLocalStatus) handleStatus=$($business.finalHandleStatus)"
    }
    if ($Scenario -eq 'DUPLICATE_CALLBACK') {
        $abnormalRows = Invoke-JsonRequest -Uri "$BaseUrl/paylens/api/anomalies" -RequestHeaders $headers -Session $payLensSession
        $matchingAbnormals = @($abnormalRows.Json.data | Where-Object { $_.tradeNo -eq $business.tradeNo })
        if ($matchingAbnormals.Count -ne 0 -or $business.finalLocalStatus -ne 'success') {
            throw "Duplicate callback stress changed the successful order or created an abnormal order; status=$($business.finalLocalStatus), abnormalRows=$($matchingAbnormals.Count)."
        }
        Write-Output "STEP duplicate-callback-idempotency=verified notifications=$($business.notificationCount) abnormalRows=0"
    }
    $business.status = 'SUCCEEDED'

    Save-RunSummary -DiagnosisStatus $diagnosis.status
    if ($diagnosisFailure) { throw $diagnosisFailure }
    Write-Output 'FLOW_RESULT=PASSED'
}
catch {
    if ($business.status -eq 'NOT_RUN') { $business.status = 'FAILED' }
    if ($diagnosis.status -eq 'NOT_RUN') { $diagnosis.status = 'NOT_RUN' }
    Save-RunSummary -DiagnosisStatus $diagnosis.status
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
