param(
    [string]$JarName = 'daxpay-union.jar',
    [ValidateSet('mock', 'openai')]
    [string]$AiMode
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if ([IO.Path]::GetFileName($JarName) -ne $JarName -or -not $JarName.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'JarName must be a JAR file name inside daxpay-start/target.'
}
$jarFile = Join-Path $repoRoot ('daxpay-start/target/' + $JarName)
$secretsFile = Join-Path $repoRoot '.paylens-local-secrets.json'
$envFile = Join-Path $repoRoot '.env'
if (-not (Test-Path -LiteralPath $jarFile)) {
    throw "Runnable JAR not found: $jarFile. Build it with: mvn -pl daxpay-start -am -DskipTests package"
}

function New-LocalAesKey {
    [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(24))
}

$rsa = $null
$environmentVariables = @(
    'PAYLENS_RSA_PRIVATE_KEY', 'PAYLENS_RSA_PUBLIC_KEY', 'PAYLENS_ENCRYPT_KEY',
    'PAYLENS_CHANNEL_ONE_KEY', 'PAYLENS_CHANNEL_TWO_KEY', 'PAYLENS_CHANNEL_THREE_KEY',
    'PAYLENS_AI_MODE', 'PAYLENS_AI_BASE_URL', 'PAYLENS_AI_MODEL', 'PAYLENS_AI_API_KEY',
    'PAYLENS_AI_CONNECT_TIMEOUT_SECONDS', 'PAYLENS_AI_REQUEST_TIMEOUT_SECONDS',
    'PAYLENS_DEMO_MCH_NO', 'PAYLENS_DEMO_APP_ID'
)
$previousEnvironment = @{}
foreach ($name in $environmentVariables) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $allowedEnvNames = @(
        'PAYLENS_AI_MODE', 'PAYLENS_AI_BASE_URL', 'PAYLENS_AI_MODEL', 'PAYLENS_AI_API_KEY',
        'PAYLENS_AI_CONNECT_TIMEOUT_SECONDS', 'PAYLENS_AI_REQUEST_TIMEOUT_SECONDS',
        'PAYLENS_DEMO_MCH_NO', 'PAYLENS_DEMO_APP_ID'
    )
    if (Test-Path -LiteralPath $envFile) {
        $lineNumber = 0
        foreach ($line in [System.IO.File]::ReadAllLines($envFile)) {
            $lineNumber++
            $entry = $line.Trim()
            if ($entry.Length -eq 0 -or $entry.StartsWith('#')) { continue }
            if ($entry -notmatch '^([A-Z][A-Z0-9_]*)\s*=(.*)$') {
                throw "Invalid .env syntax at line $lineNumber. Expected NAME=value."
            }
            $name = $matches[1]
            if ($name -notin $allowedEnvNames) {
                throw "Unsupported .env variable '$name' at line $lineNumber."
            }
            $value = $matches[2].Trim()
            if ($value.Length -ge 2 -and
                (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                 ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            elseif ($value.StartsWith('"') -or $value.StartsWith("'") -or
                    $value.EndsWith('"') -or $value.EndsWith("'")) {
                throw "Unmatched quote in .env at line $lineNumber."
            }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }

    if (Test-Path -LiteralPath $secretsFile) {
        try {
            $localSecrets = Get-Content -LiteralPath $secretsFile -Raw | ConvertFrom-Json -ErrorAction Stop
        }
        catch {
            throw "Local PayLens key file is invalid: $secretsFile. Keep the database and key file together; restore the key file before continuing."
        }
    }
    else {
        $rsa = [System.Security.Cryptography.RSA]::Create(2048)
        $localSecrets = [ordered]@{
            RsaPrivateKey = $rsa.ExportPkcs8PrivateKeyPem()
            RsaPublicKey = $rsa.ExportSubjectPublicKeyInfoPem()
            EncryptKey = New-LocalAesKey
            ChannelOneKey = New-LocalAesKey
            ChannelTwoKey = New-LocalAesKey
            ChannelThreeKey = New-LocalAesKey
        }
        $localSecrets | ConvertTo-Json | Set-Content -LiteralPath $secretsFile -Encoding utf8
    }

    foreach ($property in @('RsaPrivateKey', 'RsaPublicKey', 'EncryptKey', 'ChannelOneKey', 'ChannelTwoKey', 'ChannelThreeKey')) {
        if ([string]::IsNullOrWhiteSpace([string]$localSecrets.$property)) {
            throw "Local PayLens key file is missing '$property': $secretsFile"
        }
    }

    $env:PAYLENS_RSA_PRIVATE_KEY = $localSecrets.RsaPrivateKey
    $env:PAYLENS_RSA_PUBLIC_KEY = $localSecrets.RsaPublicKey
    $env:PAYLENS_ENCRYPT_KEY = $localSecrets.EncryptKey
    $env:PAYLENS_CHANNEL_ONE_KEY = $localSecrets.ChannelOneKey
    $env:PAYLENS_CHANNEL_TWO_KEY = $localSecrets.ChannelTwoKey
    $env:PAYLENS_CHANNEL_THREE_KEY = $localSecrets.ChannelThreeKey

    $demoMchNo = if ([string]::IsNullOrWhiteSpace($env:PAYLENS_DEMO_MCH_NO)) { 'M1784445131420' } else { $env:PAYLENS_DEMO_MCH_NO }
    $demoAppId = if ([string]::IsNullOrWhiteSpace($env:PAYLENS_DEMO_APP_ID)) { 'A6925929877811827' } else { $env:PAYLENS_DEMO_APP_ID }

    Push-Location $repoRoot
    try {
        $javaArguments = @(
            '-jar', $jarFile,
            '--spring.profiles.active=paylens',
            '--paylens.demo.enabled=true',
            "--paylens.demo.mch-no=$demoMchNo",
            "--paylens.demo.app-id=$demoAppId"
        )
        if ($AiMode) { $javaArguments += "--paylens.ai.mode=$AiMode" }
        & java @javaArguments
        exit $LASTEXITCODE
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($null -ne $rsa) { $rsa.Dispose() }
    foreach ($name in $environmentVariables) {
        $previousValue = $previousEnvironment[$name]
        if ($null -eq $previousValue) {
            Remove-Item "Env:$name" -ErrorAction SilentlyContinue
        }
        else {
            [Environment]::SetEnvironmentVariable($name, $previousValue, 'Process')
        }
    }
}
