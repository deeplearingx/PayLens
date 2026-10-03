[CmdletBinding()]
param(
    [string]$PostgresImage = 'postgres:16-alpine',
    [int]$StartupTimeoutSeconds = 75,
    [string]$DockerHost
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dockerContext = 'explicit endpoint'
$dockerHost = $DockerHost
if ([string]::IsNullOrWhiteSpace($dockerHost)) {
    $dockerContext = (& docker context show 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerContext)) {
        throw 'Docker CLI has no readable current context. This script does not switch or restart Docker contexts.'
    }
    $dockerHost = (& docker context inspect $dockerContext --format '{{.Endpoints.docker.Host}}' 2>$null).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($dockerHost)) {
        throw "Could not resolve the endpoint for the existing Docker context '$dockerContext'."
    }
}
$name = 'paylens-it-pg-' + [Guid]::NewGuid().ToString('N').Substring(0,12)
$previousDockerConfig = [Environment]::GetEnvironmentVariable('DOCKER_CONFIG', 'Process')
$dockerConfigPath = Join-Path ([IO.Path]::GetTempPath()) "paylens-docker-cli-$name"
New-Item -ItemType Directory -Path $dockerConfigPath -Force | Out-Null
$env:DOCKER_CONFIG = $dockerConfigPath
$bootstrapDatabase = 'paylens_it_admin'
$analysisDatabase = 'paylens_analysis_test'
$incidentDatabase = 'paylens_incident_test'
$username = 'paylens_test'
$password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
$containerCreated = $false

try {
    Write-Output "Starting one disposable PostgreSQL test container using existing Docker context '$dockerContext'."
    $id = & docker --host $dockerHost run --detach --name $name --label paylens.integration=true --publish '127.0.0.1::5432' --env "POSTGRES_DB=$bootstrapDatabase" --env "POSTGRES_USER=$username" --env "POSTGRES_PASSWORD=$password" $PostgresImage 2>&1
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace(($id | Out-String))) {
        throw 'Could not start the disposable PostgreSQL test container.'
    }
    $containerCreated = $true
    $portText = (& docker --host $dockerHost port $name '5432/tcp' 2>$null | Select-Object -First 1)
    if ($LASTEXITCODE -ne 0 -or $portText -notmatch ':(\d+)$') {
        throw 'Docker did not publish a host port for the disposable PostgreSQL container.'
    }
    $port = [int]$Matches[1]

    $deadline = [DateTimeOffset]::UtcNow.AddSeconds($StartupTimeoutSeconds)
    $readyChecks = 0
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        & docker --host $dockerHost exec $name pg_isready -q -U $username -d $bootstrapDatabase 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $readyChecks++
            if ($readyChecks -ge 3) { break }
        }
        else { $readyChecks = 0 }
        Start-Sleep -Seconds 1
    }
    if ($readyChecks -lt 3) { throw "Disposable PostgreSQL did not remain ready for three checks within $StartupTimeoutSeconds seconds." }

    & docker --host $dockerHost exec $name createdb -U $username $analysisDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated analysis-test database.' }
    & docker --host $dockerHost exec $name createdb -U $username $incidentDatabase
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated incident-test database.' }

    $env:PAYLENS_TEST_JDBC_USER = $username
    $env:PAYLENS_TEST_JDBC_PASSWORD = $password
    Push-Location $root
    try {
        foreach ($testRun in @(
            [pscustomobject]@{ Name = 'AnalysisTaskRepositoryIT'; Database = $analysisDatabase },
            [pscustomobject]@{ Name = 'IncidentAdjustmentIT'; Database = $incidentDatabase }
        )) {
            $env:PAYLENS_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:$port/$($testRun.Database)"
            Write-Output "Running $($testRun.Name) against its own disposable database."
            & rtk proxy mvn -pl paylens-integration-tests -am '-Dtest=NoUnitTests' '-Dsurefire.failIfNoSpecifiedTests=false' "-Dit.test=$($testRun.Name)" verify
            if ($LASTEXITCODE -ne 0) { throw "$($testRun.Name) exited with code $LASTEXITCODE." }
        }
    } finally {
        Pop-Location
    }
} finally {
    Remove-Item Env:PAYLENS_TEST_JDBC_URL -ErrorAction SilentlyContinue
    Remove-Item Env:PAYLENS_TEST_JDBC_USER -ErrorAction SilentlyContinue
    Remove-Item Env:PAYLENS_TEST_JDBC_PASSWORD -ErrorAction SilentlyContinue
    if ($containerCreated) {
        & docker --host $dockerHost rm --force $name 2>$null | Out-Null
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove this temporary test container '$name'; no other container was targeted." }
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
}
