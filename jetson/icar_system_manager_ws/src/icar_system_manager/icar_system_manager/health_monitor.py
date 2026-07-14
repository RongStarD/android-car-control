from typing import Dict, Iterable, Mapping, Set

from lifecycle_msgs.srv import GetState

from .models import GroupHealth, GroupSpec, GroupState


def normalize_node_name(name: str) -> str:
    return name.strip().lstrip("/")


class LifecycleStateMonitor:
    """Non-blocking lifecycle state cache; ordinary nodes are checked via the ROS graph."""

    def __init__(self, node):
        self.node = node
        self.clients = {}
        self.pending = {}
        self.states: Dict[str, str] = {}

    def update(self, lifecycle_nodes: Iterable[str]) -> None:
        for raw_name in lifecycle_nodes:
            name = normalize_node_name(raw_name)
            future = self.pending.get(name)
            if future is not None:
                if future.done():
                    try:
                        self.states[name] = str(future.result().current_state.label).lower()
                    except Exception:
                        self.states[name] = "query_failed"
                    self.pending.pop(name, None)
                continue

            client = self.clients.get(name)
            if client is None:
                client = self.node.create_client(GetState, "/{}/get_state".format(name))
                self.clients[name] = client
            if not client.service_is_ready():
                self.states[name] = "unavailable"
                continue
            self.pending[name] = client.call_async(GetState.Request())

    def clear(self, names: Iterable[str]) -> None:
        for raw_name in names:
            name = normalize_node_name(raw_name)
            self.states.pop(name, None)
            self.pending.pop(name, None)


class CompositeHealthMonitor:
    """Process/graph/lifecycle health with deliberately no topic freshness dependency."""

    def __init__(self, strategies, lifecycle_monitor: LifecycleStateMonitor):
        self.strategies = set(strategies)
        self.lifecycle_monitor = lifecycle_monitor

    def assess(
        self,
        spec: GroupSpec,
        process,
        visible_nodes: Set[str],
        lifecycle_states: Mapping[str, str],
    ) -> GroupHealth:
        if process is None:
            return GroupHealth(GroupState.STOPPED, False, "进程尚未启动")

        code = process.poll()
        if code is not None:
            if spec.one_shot and code == 0:
                return GroupHealth(GroupState.EXITED, True, "一次性任务已成功完成", exit_code=code)
            return GroupHealth(
                GroupState.EXITED,
                False,
                "受管进程已退出，代码 {}".format(code),
                exit_code=code,
            )

        missing = []
        if "graph" in self.strategies:
            missing = [name for name in spec.required_nodes if normalize_node_name(name) not in visible_nodes]

        inactive = {}
        if "lifecycle" in self.strategies:
            for name in spec.lifecycle_nodes:
                normalized = normalize_node_name(name)
                state = lifecycle_states.get(normalized, "unknown")
                if state != "active":
                    inactive[normalized] = state

        if not missing and not inactive:
            return GroupHealth(GroupState.READY, True, "进程及 ROS 节点状态正常")

        details = []
        if missing:
            details.append("等待节点：{}".format(", ".join(missing)))
        if inactive:
            details.append("Lifecycle 未激活：{}".format(", ".join(
                "{}={}".format(name, state) for name, state in sorted(inactive.items())
            )))
        message = "；".join(details)
        if process.elapsed() >= spec.startup_timeout_sec:
            return GroupHealth(GroupState.DEGRADED, False, "启动超时；" + message, missing, inactive)
        return GroupHealth(GroupState.STARTING, False, message, missing, inactive)


def visible_node_names(node) -> Set[str]:
    result = set()
    for name, namespace in node.get_node_names_and_namespaces():
        namespace = namespace.strip("/")
        full_name = "/".join(part for part in (namespace, name) if part)
        result.add(normalize_node_name(full_name))
        result.add(normalize_node_name(name))
    return result
