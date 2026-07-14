param(
  [string]$JetsonHost = "10.224.104.165",
  [string]$Container = "81c99e0c3f98",
  [int]$Port = 9092
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Bridge = Join-Path $ProjectRoot "jetson\mapping_bridge.py"
if (-not (Test-Path $Bridge)) { throw "Mapping bridge not found: $Bridge" }

scp $Bridge ("jetson@" + $JetsonHost + ":/tmp/mapping_bridge.py")
ssh "jetson@$JetsonHost" "docker cp /tmp/mapping_bridge.py $Container`:/tmp/mapping_bridge.py"
ssh "jetson@$JetsonHost" "docker exec $Container python3 -c 'import websockets'"
if ($LASTEXITCODE -ne 0) {
  ssh "jetson@$JetsonHost" "docker exec $Container python3 -m pip install --user websockets==9.1"
}
ssh "jetson@$JetsonHost" "docker exec $Container bash -ic 'pkill -INT -f -- \"[m]apping_bridge.py\" || true; source /opt/ros/foxy/setup.bash && nohup python3 /tmp/mapping_bridge.py --port $Port >/tmp/android-mapping-bridge.log 2>&1 &'"
Write-Host "Mapping bridge started on ws://$JetsonHost`:$Port"
