param(
  [string]$JetsonHost = "10.224.104.165",
  [string]$Container = "81c99e0c3f98"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$InspectorScript = Join-Path $ProjectRoot "jetson\inspect_navigation_topics.sh"

if (-not (Test-Path $InspectorScript)) {
  throw "Jetson navigation-topic inspector not found: $InspectorScript"
}

Write-Host "Checking ROS container $Container on $JetsonHost..."
$containerRunning = ssh "jetson@$JetsonHost" "docker inspect --format '{{.State.Running}}' $Container"
if ($LASTEXITCODE -ne 0) {
  throw "ROS container $Container was not found or could not be inspected on $JetsonHost."
}
if ($containerRunning.Trim() -ne "true") {
  throw "ROS container $Container is stopped. Start it, then start n1 and n3 or n4 before inspecting navigation topics."
}

Write-Host "Copying and running the live Nav2 topic inspector..."
Write-Host "Expected: first start n1, then start either n3 (DWA) or n4 (TEB)."
scp $InspectorScript ("jetson@" + $JetsonHost + ":/tmp/inspect_navigation_topics.sh")
if ($LASTEXITCODE -ne 0) {
  throw "Could not copy the navigation-topic inspector to $JetsonHost."
}
ssh "jetson@$JetsonHost" ("docker cp /tmp/inspect_navigation_topics.sh " + $Container + ":/tmp/inspect_navigation_topics.sh")
if ($LASTEXITCODE -ne 0) {
  throw "Could not copy the navigation-topic inspector into ROS container $Container."
}
# `bash -i` loads the container's ROS profile, including ROS_DOMAIN_ID=30.
# Without it, ros2 CLI silently queries domain 0 and finds no Nav2 nodes.
ssh "jetson@$JetsonHost" "docker exec $Container bash -ic 'bash /tmp/inspect_navigation_topics.sh'"
if ($LASTEXITCODE -ne 0) {
  throw "Could not inspect Nav2 topics in ROS container $Container."
}

Write-Host ""
Write-Host "If local/global costmap or plan topics are absent, save the output: the Nav2 parameter file must explicitly enable those publishers before the App Bridge can relay them."
