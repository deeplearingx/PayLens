[CmdletBinding()]
param(
    [ValidateRange(0, 38)][int]$MaxCalls = 38,
    [string]$ResumePath,
    [string]$RunDirectory
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $root '.env'
$casesFile = Join-Path $root 'docs/paylens/incident-evaluation-cases.json'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'The repository .env file is required for a real model evaluation.' }
if (-not (Test-Path -LiteralPath $casesFile)) { throw 'The frozen event evaluation case file is missing.' }

$allowed = @(
    'PAYLENS_AI_BASE_URL', 'PAYLENS_AI_MODEL', 'PAYLENS_AI_API_KEY',
    'PAYLENS_AI_CONNECT_TIMEOUT_SECONDS', 'PAYLENS_AI_REQUEST_TIMEOUT_SECONDS'
)
$values = @{}
$lineNumber = 0
foreach ($line in Get-Content -LiteralPath $envFile) {
    $lineNumber++
    if ($line -match '^\s*(?:#|$)') { continue }
    if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') {
        throw "Invalid .env syntax at line $lineNumber. Expected NAME=value."
    }
    $name = $Matches[1]
    if ($name -notin $allowed) { continue }
    $value = $Matches[2]
    if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    $values[$name] = $value
}
foreach ($requiredName in @('PAYLENS_AI_BASE_URL', 'PAYLENS_AI_MODEL', 'PAYLENS_AI_API_KEY')) {
    if ([string]::IsNullOrWhiteSpace($values[$requiredName])) { throw ".env must define $requiredName." }
}
try {
    $providerUri = [Uri]$values.PAYLENS_AI_BASE_URL
    if ($providerUri.Scheme -notin @('http', 'https') -or -not $providerUri.Host) { throw 'Invalid scheme or host.' }
} catch { throw 'PAYLENS_AI_BASE_URL in .env is not a valid HTTP(S) URL.' }

$environmentNames = @($allowed + @('PAYLENS_INCIDENT_EVAL_RUN', 'PAYLENS_INCIDENT_EVAL_CASES',
    'PAYLENS_INCIDENT_EVAL_MAX_CALLS', 'PAYLENS_INCIDENT_EVAL_RESUME', 'PAYLENS_INCIDENT_EVAL_RUN_DIR'))
$previous = @{}
foreach ($name in $environmentNames) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    foreach ($name in $allowed) {
        if ($values.ContainsKey($name)) { [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process') }
    }
    $env:PAYLENS_INCIDENT_EVAL_RUN = 'true'
    $env:PAYLENS_INCIDENT_EVAL_CASES = $casesFile
    $env:PAYLENS_INCIDENT_EVAL_MAX_CALLS = [string]$MaxCalls
    if ($ResumePath) {
        $resolvedResume = if ([IO.Path]::IsPathRooted($ResumePath)) { [IO.Path]::GetFullPath($ResumePath) } else { [IO.Path]::GetFullPath((Join-Path $root $ResumePath)) }
        $env:PAYLENS_INCIDENT_EVAL_RESUME = $resolvedResume
    } else {
        Remove-Item Env:PAYLENS_INCIDENT_EVAL_RESUME -ErrorAction SilentlyContinue
        if ($RunDirectory) {
            $resolvedRun = if ([IO.Path]::IsPathRooted($RunDirectory)) { [IO.Path]::GetFullPath($RunDirectory) } else { [IO.Path]::GetFullPath((Join-Path $root $RunDirectory)) }
            $env:PAYLENS_INCIDENT_EVAL_RUN_DIR = $resolvedRun
        } else {
            Remove-Item Env:PAYLENS_INCIDENT_EVAL_RUN_DIR -ErrorAction SilentlyContinue
        }
    }
    Write-Output "Starting explicit event evaluation: at most $MaxCalls requests; provider host=$($providerUri.Host); model=$($values.PAYLENS_AI_MODEL)."
    Write-Output 'The evaluator uses the production Spring AI ChatClient adapter. Each request is counted before sending; failed or interrupted requests are not retried.'
    Push-Location $root
    try {
        # Keep evaluation-only Maven writes inside the repository's ignored target directory.
        $localRepository = Join-Path $root 'target/paylens-evaluation-m2'
        & mvn "-Dmaven.repo.local=$localRepository" -B -pl paylens-analysis -DskipTests test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java `
            '-Dexec.classpathScope=test' `
            '-Dexec.mainClass=cn.daxpay.open.paylens.analysis.IncidentModelEvaluationHarness'
        if ($LASTEXITCODE -ne 0) { throw "Maven evaluation runner failed with exit code $LASTEXITCODE." }
    } finally { Pop-Location }
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
