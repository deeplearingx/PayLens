[CmdletBinding()]
param(
    [ValidateRange(0,120)][int]$MaxCalls = 120,
    [string]$RunDirectory
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $root '.env'
if (-not (Test-Path -LiteralPath $envFile)) { throw 'Missing local .env for cloud evaluation.' }
$allowed = @('PAYLENS_AI_BASE_URL','PAYLENS_AI_MODEL','PAYLENS_AI_API_KEY')
$previous = @{}
foreach ($name in @($allowed + @('PAYLENS_IMPROVEMENT_EVAL_RUN','PAYLENS_IMPROVEMENT_EVAL_MAX_CALLS','PAYLENS_IMPROVEMENT_EVAL_RUN_DIR'))) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match '^\s*(?:#|$)') { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$') { throw 'Invalid .env syntax.' }
        $name = $Matches[1]
        if ($name -notin $allowed) { continue }
        $value = $Matches[2]
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
            ($value.StartsWith("'") -and $value.EndsWith("'")))) { $value = $value.Substring(1, $value.Length - 2) }
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    foreach ($name in $allowed) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) { throw "Missing $name in .env." }
    }
    $env:PAYLENS_IMPROVEMENT_EVAL_RUN = 'true'
    $env:PAYLENS_IMPROVEMENT_EVAL_MAX_CALLS = [string]$MaxCalls
    if ($RunDirectory) {
        $env:PAYLENS_IMPROVEMENT_EVAL_RUN_DIR = [IO.Path]::GetFullPath((Join-Path $root $RunDirectory))
    }
    Push-Location $root
    try {
        & mvn -B -q -pl paylens-analysis -DskipTests test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java `
            '-Dexec.classpathScope=test' `
            '-Dexec.mainClass=cn.daxpay.open.paylens.analysis.IncidentImprovementEvaluationHarness'
        if ($LASTEXITCODE -ne 0) { throw "Evaluation runner exited $LASTEXITCODE" }
    } finally { Pop-Location }
} finally {
    foreach ($name in $previous.Keys) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
