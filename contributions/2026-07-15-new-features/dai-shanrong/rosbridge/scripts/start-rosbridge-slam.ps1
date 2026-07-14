param(
  [string]$JetsonHost = "10.39.132.165",
  [string]$Container = "81c99e0c3f98",
  [int]$Port = 9092
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$ApiScript = Join-Path $ProjectRoot "jetson\robot_app_api.py"
$BridgeScript = Join-Path $ProjectRoot "jetson\app_bridge.py"

if (-not (Test-Path $ApiScript) -or -not (Test-Path $BridgeScript)) {
  throw "Jetson bridge scripts not found: $ApiScript, $BridgeScript"
}

Write-Host "Checking ROS container $Container on $JetsonHost..."
$containerRunning = ssh "jetson@$JetsonHost" "docker inspect --format '{{.State.Running}}' $Container"
if ($LASTEXITCODE -ne 0) {
  throw "ROS container $Container was not found or could not be inspected on $JetsonHost."
}

if ($containerRunning.Trim() -ne "true") {
  Write-Host "ROS container is stopped; starting it (equivalent to shortcut 's')..."
  ssh "jetson@$JetsonHost" "docker start $Container"
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to start ROS container $Container on $JetsonHost."
  }
} else {
  Write-Host "ROS container is already running."
}

Write-Host "Checking Python compatibility for the binary App Bridge..."
ssh "jetson@$JetsonHost" "docker exec $Container python3 -c 'import sys; assert sys.version_info >= (3, 6), sys.version'"
if ($LASTEXITCODE -ne 0) {
  throw "The ROS container needs Python 3.6 or newer for the binary App Bridge."
}

ssh "jetson@$JetsonHost" "docker exec $Container python3 -c 'import websockets'"
if ($LASTEXITCODE -ne 0) {
  Write-Host "Installing Python 3.6-compatible websockets==9.1 in the ROS container..."
  ssh "jetson@$JetsonHost" "docker exec $Container python3 -m pip install --user websockets==9.1"
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to install websockets==9.1. Install that exact version in the ROS container, then rerun this script."
  }
}

Write-Host "Copying the App API and binary WebSocket bridge into the running container..."
scp $ApiScript ("jetson@" + $JetsonHost + ":/tmp/robot_app_api.py")
scp $BridgeScript ("jetson@" + $JetsonHost + ":/tmp/app_bridge.py")
ssh "jetson@$JetsonHost" ("docker cp /tmp/robot_app_api.py " + $Container + ":/tmp/robot_app_api.py")
ssh "jetson@$JetsonHost" ("docker cp /tmp/app_bridge.py " + $Container + ":/tmp/app_bridge.py")

Write-Host "Replacing ROSBridge with the binary App Bridge on port $Port..."
ssh "jetson@$JetsonHost" "docker exec $Container bash -ic 'pkill -INT -f -- ""[r]osbridge_websocket"" || true; pkill -INT -f -- ""[a]pp_bridge.py"" || true; pkill -INT -f -- ""[r]obot_app_api.py"" || true'"
ssh "jetson@$JetsonHost" "docker exec -d $Container bash -ic 'source /opt/ros/foxy/setup.bash && python3 /tmp/robot_app_api.py > /tmp/robot-app-api.log 2>&1'"
if ($LASTEXITCODE -ne 0) {
  throw "Failed to start the App control service in ROS container $Container."
}

Write-Host "Waiting for the App ROS services to register..."
ssh "jetson@$JetsonHost" "docker exec $Container bash -ic 'source /opt/ros/foxy/setup.bash && for attempt in {1..10}; do ros2 service list | grep -qx /app/start_mapping && exit 0; sleep 1; done; tail -n 80 /tmp/robot-app-api.log; exit 1'"
if ($LASTEXITCODE -ne 0) {
  throw "The App control service did not register. The container log above shows why."
}

Write-Host "Starting binary App Bridge WebSocket on port $Port..."
ssh "jetson@$JetsonHost" "docker exec -d $Container bash -ic 'source /opt/ros/foxy/setup.bash && python3 /tmp/app_bridge.py --host 0.0.0.0 --port $Port > /tmp/app-bridge.log 2>&1'"
if ($LASTEXITCODE -ne 0) {
  throw "Failed to start the binary App Bridge in ROS container $Container."
}

Write-Host "Waiting for the binary App Bridge listener..."
ssh "jetson@$JetsonHost" "docker exec $Container bash -ic 'for attempt in {1..10}; do pgrep -f ""[a]pp_bridge.py"" >/dev/null && exit 0; sleep 1; done; tail -n 80 /tmp/app-bridge.log; exit 1'"
if ($LASTEXITCODE -ne 0) {
  throw "The binary App Bridge did not start. The container log above shows why."
}

Write-Host "Started. Set the Android App to Jetson App Bridge, host $JetsonHost, port $Port."
Write-Host "The App now uses binary WebSocket packets; rosbridge_server is not used."
