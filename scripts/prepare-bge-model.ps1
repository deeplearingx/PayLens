param(
    [string]$Output = "models/bge-small-zh-v1.5",
    [string]$Python = "python"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$venv = Join-Path $repoRoot ".venv-bge-export"
$pythonExe = Join-Path $venv "Scripts/python.exe"
$requirements = Join-Path $PSScriptRoot "requirements-bge-model.txt"
$exportScript = Join-Path $PSScriptRoot "prepare_bge_model.py"
$outputPath = Join-Path $repoRoot $Output

if (-not (Test-Path $pythonExe)) {
    $pythonVersionText = & $Python --version
    if ($LASTEXITCODE -ne 0 -or $pythonVersionText -notmatch 'Python 3\.(11|12|13)\.') {
        throw "模型导出脚本要求 Python 3.11、3.12 或 3.13；当前版本：$pythonVersionText"
    }
    & $Python -m venv $venv
    if ($LASTEXITCODE -ne 0) { throw "创建模型导出 Python 环境失败" }
}
$venvVersionText = & $pythonExe --version
if ($LASTEXITCODE -ne 0 -or $venvVersionText -notmatch 'Python 3\.(11|12|13)\.') {
    throw "模型导出虚拟环境要求 Python 3.11、3.12 或 3.13；当前版本：$venvVersionText"
}
& $pythonExe -m pip install -r $requirements
if ($LASTEXITCODE -ne 0) { throw "安装固定版本的 ONNX 导出依赖失败" }
& $pythonExe $exportScript --output $outputPath
if ($LASTEXITCODE -ne 0) { throw "BGE ONNX 导出或校验失败" }
