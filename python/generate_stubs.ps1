$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $PSScriptRoot "generated"
$Python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

New-Item -ItemType Directory -Force -Path $Out | Out-Null

$Args = @(
    "-m", "grpc_tools.protoc",
    "-I", "$Root\src\main\proto",
    "--python_out=$Out",
    "--grpc_python_out=$Out",
    "$Root\src\main\proto\gymcraft\gym\action\components.proto",
    "$Root\src\main\proto\gymcraft\gym\action\mc_action.proto",
    "$Root\src\main\proto\gymcraft\gym\observation\inventory.proto",
    "$Root\src\main\proto\gymcraft\gym\observation\entity.proto",
    "$Root\src\main\proto\gymcraft\gym\observation\components.proto",
    "$Root\src\main\proto\gymcraft\gym\observation\mc_observation.proto",
    "$Root\src\main\proto\gymcraft\gym\rpc\env_service.proto"
)

& $Python $Args
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

"Generated Python gRPC stubs in $Out"
