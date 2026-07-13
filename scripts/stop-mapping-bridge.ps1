param(
  [string]$JetsonHost = "10.224.104.165",
  [string]$Container = "81c99e0c3f98"
)

$ErrorActionPreference = "Stop"
ssh "jetson@$JetsonHost" "docker exec $Container bash -ic 'pkill -INT -f -- \"[m]apping_bridge.py\" || true; pkill -INT -f -- \"[m]ap_gmapping_launch.py\" || true'"
Write-Host "Mapping bridge and GMapping stopped."
