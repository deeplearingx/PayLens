[CmdletBinding()]
param(
    [ValidateRange(1, 20)]
    [int]$Rounds = 20,
    [ValidateSet('ConfirmIgnore', 'SyncCallback', 'DuplicateCallback100')]
    [string]$RaceMode = 'ConfirmIgnore',
    [string]$JarName = 'daxpay-start-validation4.jar',
    [string]$DockerHost,
    [switch]$BrowserSmokeTest,
    [ValidateRange(1024, 65535)]
    [int]$AppPort = 10099,
    [int]$StartupTimeoutSeconds = 150
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dockerContext = 'explicit endpoint'
$contextDockerHost = $DockerHost
if ([string]::IsNullOrWhiteSpace($contextDockerHost)) {
    $dockerContext = (& docker context show 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerContext)) {
        throw 'Docker CLI has no readable current context. This test does not switch or restart Docker.'
    }
    $contextDockerHost = (& docker context inspect $dockerContext --format '{{.Endpoints.docker.Host}}' 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($contextDockerHost)) {
        throw "Could not resolve the endpoint for the existing Docker context '$dockerContext'."
    }
}

$portProbe = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, $AppPort)
try { $portProbe.Start() } catch { throw "Isolated PayLens test port $AppPort is already in use." } finally { $portProbe.Stop() }

$runId = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$modeSlug = $RaceMode.ToLowerInvariant()
$containerPrefix = "paylens-$modeSlug-$suffix"
$previousDockerConfig = [Environment]::GetEnvironmentVariable('DOCKER_CONFIG', 'Process')
$dockerConfigPath = Join-Path ([IO.Path]::GetTempPath()) "paylens-docker-cli-$suffix"
New-Item -ItemType Directory -Path $dockerConfigPath -Force | Out-Null
$env:DOCKER_CONFIG = $dockerConfigPath
$dockerHost = $contextDockerHost
$postgresName = "$containerPrefix-pg"
$redisName = "$containerPrefix-redis"
$artemisName = "$containerPrefix-artemis"
$containerNames = [System.Collections.Generic.List[string]]::new()
$resultRoot = Join-Path $repoRoot "docs/paylens/integration-results/payment-$modeSlug-$runId"
$logRoot = Join-Path $repoRoot "daxpay-start/target/paylens-payment-$modeSlug/$runId"
New-Item -ItemType Directory -Force -Path $resultRoot, $logRoot | Out-Null
$summaryPath = Join-Path $resultRoot 'summary.json'
$pgPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$redisPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$artemisPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$launcher = $null
$javaPid = $null
$currentStep = 'initialize'
$failureType = $null
$roundResults = [System.Collections.Generic.List[object]]::new()
$timer = [Diagnostics.Stopwatch]::StartNew()
$envNames = @(
    'PAYLENS_SERVER_ADDRESS', 'PAYLENS_SERVER_PORT', 'PAYLENS_DB_URL', 'PAYLENS_DB_USER', 'PAYLENS_DB_PASSWORD',
    'PAYLENS_REDIS_HOST', 'PAYLENS_REDIS_PORT', 'PAYLENS_REDIS_DATABASE', 'PAYLENS_REDIS_PASSWORD',
    'PAYLENS_ARTEMIS_URL', 'PAYLENS_ARTEMIS_USER', 'PAYLENS_ARTEMIS_PASSWORD', 'PAYLENS_DEMO_MCH_NO',
    'PAYLENS_DEMO_APP_ID', 'PAYLENS_AI_MODE', 'PAYLENS_DEMO_DUPLICATE_CALLBACK_COUNT',
    'PAYLENS_TEST_NON_ADMIN', 'PAYLENS_NONADMIN_ACCOUNT', 'PAYLENS_BROWSER_BASE_URL',
    'PAYLENS_BROWSER_ACCOUNT', 'PAYLENS_BROWSER_PASSWORD', 'PAYLENS_BROWSER_SCREENSHOT',
    'PAYLENS_TEST_PERMISSION_MATRIX'
)
$oldEnvironment = @{}
foreach ($name in $envNames) { $oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

function Start-DisposableContainer {
    param([string]$Name, [string[]]$Arguments)
    $output = & docker --host $dockerHost run --detach --name $Name --label 'paylens.integration.kind=payment-disposition' @Arguments 2>&1
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($output | Out-String))) {
        throw "Could not start disposable container '$Name' (docker exit=$LASTEXITCODE): $($output -join ' ')"
    }
    $containerNames.Add($Name)
}

function Get-PublishedPort {
    param([string]$Name, [string]$ContainerPort)
    $line = & docker --host $dockerHost port $Name $ContainerPort 2>$null | Select-Object -First 1
    if ($LASTEXITCODE -ne 0 -or $line -notmatch ':(\d+)$') { throw "Could not read the published port for '$Name'." }
    return [int]$Matches[1]
}

function Wait-TcpPort {
    param([int]$Port, [int]$TimeoutSeconds)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $client = [Net.Sockets.TcpClient]::new()
        try {
            $pending = $client.BeginConnect('127.0.0.1', $Port, $null, $null)
            if ($pending.AsyncWaitHandle.WaitOne(800) -and $client.Connected) { return }
        } catch { }
        finally { $client.Dispose() }
        Start-Sleep -Milliseconds 500
    }
    throw "TCP service on 127.0.0.1:$Port was not ready in time."
}

function Wait-Postgres {
    param([int]$TimeoutSeconds)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    $consecutiveReadyChecks = 0
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        & docker --host $dockerHost exec $postgresName pg_isready -q -U paylens -d daxpay 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $consecutiveReadyChecks++
            if ($consecutiveReadyChecks -ge 3) { return }
        }
        else { $consecutiveReadyChecks = 0 }
        Start-Sleep -Seconds 1
    }
    throw 'Disposable PostgreSQL did not remain ready for three consecutive checks.'
}

function Wait-Redis {
    param([int]$TimeoutSeconds)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        & docker --host $dockerHost exec $redisName redis-cli --no-auth-warning -a $redisPassword ping 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) { return }
        Start-Sleep -Milliseconds 500
    }
    throw 'Disposable Redis did not become ready.'
}

function Wait-Artemis {
    param([int]$TimeoutSeconds)
    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $brokerLog = & docker --host $dockerHost logs $artemisName 2>&1
        if ($brokerLog | Select-String -SimpleMatch 'AMQ221007: Server is now active' -Quiet) { return }
        $state = & docker --host $dockerHost inspect $artemisName --format '{{.State.Status}}' 2>$null
        if ($state -ne 'running') {
            $exitState = & docker --host $dockerHost inspect $artemisName --format '{{.State.ExitCode}} {{.State.OOMKilled}} {{.State.Error}}' 2>$null
            $diagnostics = @($brokerLog | Select-String -Pattern 'ERROR|Exception|failed|AMQ221|AMQ222' -CaseSensitive:$false |
                Select-Object -Last 12 | ForEach-Object { $_.Line -replace '(?i)(password|secret|token)\s*[=:]\s*[^, ]+', '$1=[REDACTED]' }) -join ' | '
            throw "Disposable Artemis exited during startup (state=$state exit=$exitState). Broker diagnostics: $diagnostics"
        }
        Start-Sleep -Seconds 1
    }
    throw 'Disposable Artemis did not report that the server became active.'
}

$summary = [ordered]@{
    runId = $runId
    startedAt = [DateTimeOffset]::Now.ToString('o')
    dockerContext = $dockerContext
    isolatedImages = @('postgres:16-alpine', 'redis:7-alpine', 'apache/activemq-artemis:2.37.0-alpine')
    roundsRequested = $Rounds
    raceMode = $RaceMode
    jarName = $JarName
    roundsPassed = 0
    confirmWins = 0
    ignoreWins = 0
    syncSucceeded = 0
    callbackSucceeded = 0
    notificationsSent = 0
    notificationRowsVerified = 0
    durationMs = $null
    status = 'RUNNING'
    rounds = @()
    failureStep = $null
    failureType = $null
    browserTest = 'NOT_RUN'
    browserScreenshot = $null
}

try {
    $currentStep = 'start-isolated-infrastructure'
    Start-DisposableContainer -Name $postgresName -Arguments @(
        '--publish', '127.0.0.1::5432', '--env', 'POSTGRES_DB=daxpay', '--env', 'POSTGRES_USER=paylens',
        '--env', "POSTGRES_PASSWORD=$pgPassword", 'postgres:16-alpine'
    )
    Start-DisposableContainer -Name $redisName -Arguments @(
        '--publish', '127.0.0.1::6379', 'redis:7-alpine', 'redis-server', '--requirepass', $redisPassword
    )
    Start-DisposableContainer -Name $artemisName -Arguments @(
        '--publish', '127.0.0.1::61616', '--env', 'ARTEMIS_USER=paylens', '--env', "ARTEMIS_PASSWORD=$artemisPassword",
        '--env', 'ANONYMOUS_LOGIN=false', 'apache/activemq-artemis:2.37.0-alpine'
    )
    $postgresPort = Get-PublishedPort -Name $postgresName -ContainerPort '5432/tcp'
    $redisPort = Get-PublishedPort -Name $redisName -ContainerPort '6379/tcp'
    $artemisPort = Get-PublishedPort -Name $artemisName -ContainerPort '61616/tcp'
    Wait-Postgres -TimeoutSeconds 90
    Wait-Redis -TimeoutSeconds 60
    Wait-Artemis -TimeoutSeconds 120
    Wait-TcpPort -Port $artemisPort -TimeoutSeconds 120
    Write-Output "ISOLATED_SERVICE_ENDPOINTS postgres=$postgresPort redis=$redisPort artemis=$artemisPort app=$AppPort"

    $currentStep = 'initialize-disposable-database'
    $schemaFile = (Resolve-Path (Join-Path $repoRoot '_config/sql/table.sql')).Path
    $dataFile = (Resolve-Path (Join-Path $repoRoot '_config/sql/data.sql')).Path
    & docker --host $dockerHost cp $schemaFile ("{0}:/tmp/paylens-table.sql" -f $postgresName)
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the DaxPay schema into the disposable PostgreSQL container.' }
    & docker --host $dockerHost exec $postgresName psql -U paylens -d daxpay -v ON_ERROR_STOP=1 -f /tmp/paylens-table.sql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply the DaxPay schema to the disposable PostgreSQL container.' }
    & docker --host $dockerHost cp $dataFile ("{0}:/tmp/paylens-data.sql" -f $postgresName)
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy the DaxPay seed data into the disposable PostgreSQL container.' }
    & docker --host $dockerHost exec $postgresName psql -U paylens -d daxpay -v ON_ERROR_STOP=1 -f /tmp/paylens-data.sql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not apply the DaxPay seed data to the disposable PostgreSQL container.' }
    $permissionSeed = Join-Path $PSScriptRoot 'paylens-permission-test-seed.sql'
    & docker --host $dockerHost cp $permissionSeed "${postgresName}:/tmp/paylens-permission-test-seed.sql" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not copy PayLens permission test accounts into the disposable PostgreSQL container.' }
    & docker --host $dockerHost exec $postgresName psql -U paylens -d daxpay -v ON_ERROR_STOP=1 `
        -f /tmp/paylens-permission-test-seed.sql | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated PayLens permission test accounts.' }

    $currentStep = 'start-isolated-paylens-application'
    $env:PAYLENS_SERVER_ADDRESS = '127.0.0.1'
    $env:PAYLENS_SERVER_PORT = [string]$AppPort
    $env:PAYLENS_DB_URL = "jdbc:postgresql://127.0.0.1:$postgresPort/daxpay"
    $env:PAYLENS_DB_USER = 'paylens'
    $env:PAYLENS_DB_PASSWORD = $pgPassword
    $env:PAYLENS_REDIS_HOST = '127.0.0.1'
    $env:PAYLENS_REDIS_PORT = [string]$redisPort
    $env:PAYLENS_REDIS_DATABASE = '0'
    $env:PAYLENS_REDIS_PASSWORD = $redisPassword
    $env:PAYLENS_ARTEMIS_URL = "tcp://127.0.0.1:$artemisPort"
    $env:PAYLENS_ARTEMIS_USER = 'paylens'
    $env:PAYLENS_ARTEMIS_PASSWORD = $artemisPassword
    $env:PAYLENS_DEMO_MCH_NO = 'M1784445131420'
    $env:PAYLENS_DEMO_APP_ID = 'A6925929877811827'
    $env:PAYLENS_AI_MODE = 'mock'
    $env:PAYLENS_TEST_NON_ADMIN = '1'
    $env:PAYLENS_NONADMIN_ACCOUNT = 'csqysh'
    if ($RaceMode -eq 'DuplicateCallback100') { $env:PAYLENS_DEMO_DUPLICATE_CALLBACK_COUNT = '100' }
    else { $env:PAYLENS_DEMO_DUPLICATE_CALLBACK_COUNT = '2' }
    $launcherScript = Join-Path $PSScriptRoot 'start-paylens.ps1'
    $launcher = Start-Process -FilePath (Join-Path $PSHOME 'pwsh.exe') `
        -ArgumentList @('-NoLogo', '-NoProfile', '-File', $launcherScript, '-JarName', $JarName, '-AiMode', 'mock') `
        -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logRoot 'app.stdout.log') `
        -RedirectStandardError (Join-Path $logRoot 'app.stderr.log')
    $healthDeadline = [DateTimeOffset]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $appReady = $false
    while ([DateTimeOffset]::UtcNow -lt $healthDeadline) {
        if ($launcher.HasExited) { throw 'Isolated PayLens launcher exited before health became UP; inspect target log files.' }
        try {
            $health = Invoke-RestMethod -Uri "http://127.0.0.1:$AppPort/actuator/health" -TimeoutSec 3
            if ($health.status -eq 'UP') { $appReady = $true; break }
        } catch { }
        Start-Sleep -Seconds 2
    }
    if (-not $appReady) { throw "Isolated PayLens did not become healthy within $StartupTimeoutSeconds seconds." }

    $currentStep = "run-$modeSlug"
    $e2eScript = Join-Path $PSScriptRoot 'test-paylens-e2e.ps1'
    for ($round = 1; $round -le $Rounds; $round++) {
        $env:PAYLENS_TEST_PERMISSION_MATRIX = if ($round -eq 1) { '1' } else { '0' }
        $roundWatch = [Diagnostics.Stopwatch]::StartNew()
        $roundName = 'round-{0:D2}' -f $round
        $roundDirectory = Join-Path $logRoot $roundName
        $e2eArguments = @('-NoLogo', '-NoProfile', '-File', $e2eScript,
            '-BaseUrl', "http://127.0.0.1:$AppPort", '-ResultDirectory', $roundDirectory)
        if ($RaceMode -eq 'ConfirmIgnore') { $e2eArguments += @('-Scenario', 'LATE_SUCCESS', '-ConcurrentDisposition') }
        elseif ($RaceMode -eq 'SyncCallback') { $e2eArguments += @('-Scenario', 'LATE_SUCCESS', '-ConcurrentSyncCallback') }
        else { $e2eArguments += @('-Scenario', 'DUPLICATE_CALLBACK') }
        & (Join-Path $PSHOME 'pwsh.exe') @e2eArguments
        if ($LASTEXITCODE -ne 0) { throw "End-to-end race round $round failed." }
        $roundWatch.Stop()
        $runSummaryPath = Join-Path $roundDirectory 'summary.json'
        $runSummary = Get-Content -Raw -LiteralPath $runSummaryPath | ConvertFrom-Json
        if ($RaceMode -eq 'DuplicateCallback100') {
            if ([string]$runSummary.business.tradeNo -notmatch '^[A-Za-z0-9_-]+$') { throw 'Generated trade number was not safe for the isolated SQL assertion.' }
            $tradeNoForSql = [string]$runSummary.business.tradeNo
            $callbackSql = "SELECT count(*) FROM pay_callback_record WHERE trade_no='$tradeNoForSql' AND callback_type='pay'"
            $callbackRows = & docker --host $dockerHost exec $postgresName psql -At -U paylens -d daxpay `
                -c $callbackSql 2>&1
            $callbackQueryExitCode = $LASTEXITCODE
            $callbackCountText = [string](@($callbackRows | ForEach-Object { [string]$_ } |
                Where-Object { $_ -match '^\s*\d+\s*$' } | Select-Object -Last 1))
            if ($callbackQueryExitCode -ne 0 -or $callbackCountText -notmatch '^\d+$' -or [int]$callbackCountText -ne 100) {
                throw "Expected exactly 100 persisted payment callback audit records, got '$($callbackRows -join ' | ')' (psql exit=$callbackQueryExitCode)."
            }
            $summary.notificationRowsVerified += [int]$callbackCountText
        }
        if ($runSummary.business.status -ne 'SUCCEEDED' -or $runSummary.diagnosis.status -ne 'SUCCEEDED') {
            throw "End-to-end $RaceMode round $round did not complete both business checks and diagnosis."
        }
        $winner = $null
        $race = @()
        if ($RaceMode -eq 'ConfirmIgnore') {
            $race = @($runSummary.business.dispositionRace)
            $winners = @($race | Where-Object { $_.succeeded })
            if ($winners.Count -ne 1) { throw "Confirm/ignore round $round did not produce exactly one successful action." }
            $winner = [string]$winners[0].action
            if ($winner -eq 'confirm') { $summary.confirmWins++ } elseif ($winner -eq 'ignore') { $summary.ignoreWins++ }
        } elseif ($RaceMode -eq 'SyncCallback') {
            $race = @($runSummary.business.syncCallbackRace)
            if ($race.Count -ne 2 -or @($race | Where-Object { $_.succeeded }).Count -eq 0) {
                throw "Sync/callback round $round did not complete both calls with at least one successful result."
            }
            if (@($race | Where-Object { $_.action -eq 'sync' -and $_.succeeded }).Count -gt 0) { $summary.syncSucceeded++ }
            if (@($race | Where-Object { $_.action -eq 'callback' -and $_.succeeded }).Count -gt 0) { $summary.callbackSucceeded++ }
        } else {
            if ([int]$runSummary.business.notificationCount -ne 100) { throw "Duplicate callback round $round did not send exactly 100 notifications." }
            $summary.notificationsSent += [int]$runSummary.business.notificationCount
        }
        $summary.roundsPassed++
        $roundResults.Add([pscustomobject]@{
            round = $round
            business = $runSummary.business.status
            diagnosis = $runSummary.diagnosis.status
            raceMode = $RaceMode
            winner = $winner
            actionResults = $race
            notificationCount = [int]$runSummary.business.notificationCount
            persistedCallbackRows = if ($RaceMode -eq 'DuplicateCallback100') { [int]$callbackCountText } else { 0 }
            finalLocalStatus = [string]$runSummary.business.finalLocalStatus
            finalHandleStatus = [string]$runSummary.business.finalHandleStatus
            elapsedMs = $roundWatch.ElapsedMilliseconds
            runDirectory = $roundDirectory
        })
        Write-Output "ROUND_RESULT=$round/$Rounds mode=$RaceMode winner=$winner notifications=$($runSummary.business.notificationCount) localStatus=$($runSummary.business.finalLocalStatus) handleStatus=$($runSummary.business.finalHandleStatus)"
    }
    if ($BrowserSmokeTest) {
        $currentStep = 'headless-browser-workbench-flow'
        $env:PAYLENS_BROWSER_BASE_URL = "http://127.0.0.1:$AppPort"
        $env:PAYLENS_BROWSER_ACCOUNT = if ($env:PAYLENS_DEMO_ACCOUNT) { $env:PAYLENS_DEMO_ACCOUNT } else { 'bootx' }
        if ($env:PAYLENS_DEMO_PASSWORD) {
            $env:PAYLENS_BROWSER_PASSWORD = $env:PAYLENS_DEMO_PASSWORD
        } else {
            $credentialDoc = Get-Content -LiteralPath (Join-Path $repoRoot '_config/sql/README.md') -Raw
            $credentialMatch = [regex]::Match($credentialDoc, '开发默认密码 \*\*(?<value>[^*]+)\*\*')
            if (-not $credentialMatch.Success) { throw 'Could not obtain the documented local demo password for the browser smoke test.' }
            $env:PAYLENS_BROWSER_PASSWORD = $credentialMatch.Groups['value'].Value
        }
        $env:PAYLENS_BROWSER_SCREENSHOT = Join-Path $resultRoot 'paylens-workbench.png'
        & rtk proxy node (Join-Path $PSScriptRoot 'test-paylens-browser.mjs')
        if ($LASTEXITCODE -ne 0) { throw 'The authenticated browser workflow did not pass.' }
        $summary.browserTest = 'PASSED'
        $summary.browserScreenshot = $env:PAYLENS_BROWSER_SCREENSHOT
    }
    $summary.status = 'PASSED'
} catch {
    $failureType = $_.Exception.GetType().Name
    $summary.status = 'FAILED'
    $summary.failureStep = $currentStep
    $summary.failureType = $failureType
    Write-Output "INTEGRATION_FAILED step=$currentStep type=$failureType"
    throw
} finally {
    $timer.Stop()
    $summary.rounds = @($roundResults)
    $summary.durationMs = $timer.ElapsedMilliseconds
    $summary.completedAt = [DateTimeOffset]::Now.ToString('o')

    $listeners = @(Get-NetTCPConnection -LocalPort $AppPort -State Listen -ErrorAction SilentlyContinue)
    foreach ($listener in $listeners) {
        $candidate = Get-CimInstance Win32_Process -Filter "ProcessId = $($listener.OwningProcess)" -ErrorAction SilentlyContinue
        if ($null -ne $candidate -and $candidate.Name -eq 'java.exe' -and $candidate.CommandLine -match [regex]::Escape($JarName)) {
            $javaPid = [int]$candidate.ProcessId
            Stop-Process -Id $javaPid -Force -ErrorAction SilentlyContinue
        }
    }
    if ($null -ne $launcher -and -not $launcher.HasExited) {
        $launcherInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($launcher.Id)" -ErrorAction SilentlyContinue
        if ($null -ne $launcherInfo -and $launcherInfo.Name -match '^pwsh(\.exe)?$' -and $launcherInfo.CommandLine -match 'start-paylens\.ps1') {
            Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue
        }
    }
    foreach ($name in $containerNames.ToArray()) {
        & docker --host $dockerHost rm --force $name 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove this test container '$name'; no existing container was targeted." }
    }
    foreach ($name in $envNames) {
        $old = $oldEnvironment[$name]
        if ($null -eq $old) { Remove-Item "Env:$name" -ErrorAction SilentlyContinue }
        else { [Environment]::SetEnvironmentVariable($name, $old, 'Process') }
    }
    if ($null -eq $previousDockerConfig) { Remove-Item Env:DOCKER_CONFIG -ErrorAction SilentlyContinue }
    else { [Environment]::SetEnvironmentVariable('DOCKER_CONFIG', $previousDockerConfig, 'Process') }
    if (Test-Path -LiteralPath $dockerConfigPath) {
        $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        $resolvedDockerConfig = [IO.Path]::GetFullPath($dockerConfigPath)
        if (-not $resolvedDockerConfig.StartsWith($resolvedTemp, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing to remove a temporary Docker config directory outside the system temp directory.'
        }
        Remove-Item -LiteralPath $resolvedDockerConfig -Recurse -Force
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $summaryPath -Encoding utf8
    Write-Output "RESULT_FILE=$summaryPath"
    Write-Output "INFRASTRUCTURE_CLEANED=$($containerNames.Count)"
}

if ($summary.status -ne 'PASSED') { exit 1 }
