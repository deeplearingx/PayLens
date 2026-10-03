$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$composeFile = Join-Path $repoRoot 'paylens-compose.yml'
$schemaFile = Join-Path $repoRoot '_config/sql/table.sql'
$dataFile = Join-Path $repoRoot '_config/sql/data.sql'

docker compose -f $composeFile cp $schemaFile 'postgres:/tmp/paylens-table.sql'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
docker compose -f $composeFile exec -T postgres psql -U paylens -d daxpay -v ON_ERROR_STOP=1 -f /tmp/paylens-table.sql
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

docker compose -f $composeFile cp $dataFile 'postgres:/tmp/paylens-data.sql'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
docker compose -f $composeFile exec -T postgres psql -U paylens -d daxpay -v ON_ERROR_STOP=1 -f /tmp/paylens-data.sql
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Output 'PayLens database schema and seed data loaded. Run this only once on an empty database.'
