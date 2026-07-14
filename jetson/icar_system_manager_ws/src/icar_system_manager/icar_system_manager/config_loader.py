from pathlib import Path
from typing import Any, Dict

import yaml

from .models import GroupSpec, ManagerConfig, ModeSpec, SystemMode


def _string_mapping(value: Any, field_name: str) -> Dict[str, str]:
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise ValueError("{} must be a mapping".format(field_name))
    return {str(key): str(item) for key, item in value.items()}


def load_manager_config(path: str) -> ManagerConfig:
    config_path = Path(path)
    with config_path.open("r", encoding="utf-8") as stream:
        raw = yaml.safe_load(stream) or {}

    raw_groups = raw.get("groups")
    raw_modes = raw.get("modes")
    if not isinstance(raw_groups, dict) or not raw_groups:
        raise ValueError("groups must be a non-empty mapping")
    if not isinstance(raw_modes, dict) or not raw_modes:
        raise ValueError("modes must be a non-empty mapping")

    groups = {}
    for name, item in raw_groups.items():
        if not isinstance(item, dict):
            raise ValueError("group {} must be a mapping".format(name))
        command = item.get("command")
        if not isinstance(command, list) or not command or not all(isinstance(part, str) for part in command):
            raise ValueError("group {} command must be a non-empty string list".format(name))
        groups[str(name)] = GroupSpec(
            name=str(name),
            command=list(command),
            required_nodes=[str(node).lstrip("/") for node in item.get("required_nodes", [])],
            lifecycle_nodes=[str(node).lstrip("/") for node in item.get("lifecycle_nodes", [])],
            startup_timeout_sec=float(item.get("startup_timeout_sec", 45.0)),
            shutdown_timeout_sec=float(item.get("shutdown_timeout_sec", 8.0)),
            one_shot=bool(item.get("one_shot", False)),
        )

    modes = {}
    for mode_name, item in raw_modes.items():
        mode = SystemMode.parse(str(mode_name))
        if not isinstance(item, dict) or not isinstance(item.get("groups", []), list):
            raise ValueError("mode {} must contain a groups list".format(mode_name))
        mode_groups = [str(group) for group in item.get("groups", [])]
        unknown = [group for group in mode_groups if group not in groups]
        if unknown:
            raise ValueError("mode {} references unknown groups: {}".format(mode.value, ", ".join(unknown)))
        if len(mode_groups) != len(set(mode_groups)):
            raise ValueError("mode {} contains duplicate groups".format(mode.value))
        modes[mode] = ModeSpec(mode=mode, groups=mode_groups)

    missing_modes = [mode.value for mode in SystemMode if mode not in modes]
    if missing_modes:
        raise ValueError("missing mode definitions: {}".format(", ".join(missing_modes)))
    if modes[SystemMode.IDLE].groups:
        raise ValueError("idle mode must not start any groups")

    strategies = [str(item).lower() for item in raw.get("health", {}).get(
        "strategies", ["process", "graph", "lifecycle"]
    )]
    supported = {"process", "graph", "lifecycle"}
    unsupported = [item for item in strategies if item not in supported]
    if unsupported:
        raise ValueError("unsupported health strategies: {}".format(", ".join(unsupported)))
    if "process" not in strategies:
        raise ValueError("process health strategy is mandatory")

    return ManagerConfig(
        environment=_string_mapping(raw.get("environment"), "environment"),
        groups=groups,
        modes=modes,
        health_strategies=strategies,
        graph_settle_timeout_sec=float(raw.get("graph_settle_timeout_sec", 3.0)),
    )
