$ErrorActionPreference = "Stop"

$ProjectRoot = Resolve-Path "$PSScriptRoot\..\..\.."
$Out = Join-Path $PSScriptRoot "src"
$Python = Join-Path $PSScriptRoot ".venv\Scripts\python.exe"

New-Item -ItemType Directory -Force -Path $Out | Out-Null

$Args = @(
    "-m", "grpc_tools.protoc",
    "-I", "$ProjectRoot\src\main\proto",
    "--python_out=$Out",
    "--pyi_out=$Out",
    "--grpc_python_out=$Out",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\action.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\step_move.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\move_to.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\set_attack_target.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\attack_once.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\noop.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\jump.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\break_block.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\set_block.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\action\components\update_interesting_blocks.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\observation.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\common\entity_view.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\common\block_view.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\common\item_stack_view.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\components\self.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\components\world.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\components\nearby_entities.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\components\nearby_blocks.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\components\interesting_blocks.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\observation\components\inventory.proto",
    "$ProjectRoot\src\main\proto\gymcraft\gym\rpc\env_service.proto"
)

& $Python $Args
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

"Generated Python gRPC stubs in $Out"
