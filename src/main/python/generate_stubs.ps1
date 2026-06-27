$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path "$PSScriptRoot\..\..\.."
$Out = Join-Path $PSScriptRoot "src"
$Python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

New-Item -ItemType Directory -Force -Path $Out | Out-Null

$Args = @(
    "-m", "grpc_tools.protoc",
    "-I", "$ProjectRoot\src\main\proto",
    "--python_out=$Out",
    "--grpc_python_out=$Out",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\mc_action.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\inventory.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\entity.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\components.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\mc_observation.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\rpc\env_service.proto"
)

& $Python $Args
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

"Generated Python gRPC stubs in $Out"
