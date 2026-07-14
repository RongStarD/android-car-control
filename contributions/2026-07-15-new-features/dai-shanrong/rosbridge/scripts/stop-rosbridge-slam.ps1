param(
  [string]$JetsonHost = "10.39.132.165",
  [string]$Container = "81c99e0c3f98",
  [switch]$StopContainer
)

$ErrorActionPreference = "Stop"

Write-Host "Checking ROS container $Container on $JetsonHost..."
$containerRunning = ssh "jetson@$JetsonHost" "docker inspect --format '{{.State.Running}}' $Container"
if ($LASTEXITCODE -ne 0) {
  throw "ROS container $Container was not found or could not be inspected on $JetsonHost."
}

if ($containerRunning.Trim() -ne "true") {
  Write-Host "ROS container is already stopped; no App Bridge or App services are running."
  exit 0
}

Write-Host "Sending a zero-velocity command before shutdown..."
ssh "jetson@$JetsonHost" "docker exec $Container bash -ic 'source /opt/ros/foxy/setup.bash && ros2 topic pub --once /cmd_vel geometry_msgs/msg/Twist ""{linear: {x: 0.0, y: 0.0, z: 0.0}, angular: {x: 0.0, y: 0.0, z: 0.0}}"" >/dev/null 2>&1 || true'"

Write-Host "Stopping the binary App Bridge, App API, mapping, and navigation launches..."
# The patterns are quoted in the container shell so Bash does not expand [r]
# before pkill receives its regular expression.
ssh "jetson@$JetsonHost" "docker exec $Container bash -ic 'pkill -INT -f -- ""[a]pp_bridge.py"" || true; pkill -INT -f -- ""[r]obot_app_api.py"" || true; pkill -INT -f -- ""[r]osbridge_websocket"" || true; pkill -INT -f -- ""[m]ap_gmapping_launch.py"" || true; pkill -INT -f -- ""[l]aser_bringup_launch.py"" || true; pkill -INT -f -- ""[n]avigation_dwa_launch.py"" || true; pkill -INT -f -- ""[n]avigation_teb_launch.py"" || true'"
if ($LASTEXITCODE -ne 0) {
  throw "One or more ROS processes could not be stopped in container $Container."
}

if ($StopContainer) {
  Write-Host "Stopping ROS container $Container..."
  ssh "jetson@$JetsonHost" "docker stop $Container"
  if ($LASTEXITCODE -ne 0) {
    throw "Failed to stop ROS container $Container."
  }
  Write-Host "Stopped ROS services and the container."
} else {
  Write-Host "Stopped the binary App Bridge and App-managed ROS processes. The container is still running."
  Write-Host "Use -StopContainer to stop the Docker container as well."
}
