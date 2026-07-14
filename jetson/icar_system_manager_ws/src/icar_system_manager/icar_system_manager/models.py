from dataclasses import dataclass, field
from enum import Enum
from typing import Dict, List, Mapping, Optional


class SystemMode(str, Enum):
    IDLE = "idle"
    MANUAL = "manual"
    SLAM = "slam"
    NAV_DWA = "nav_dwa"
    NAV_TEB = "nav_teb"

    @classmethod
    def parse(cls, value: str) -> "SystemMode":
        return cls(value.strip().lower())


class RuntimeState(str, Enum):
    INACTIVE = "inactive"
    IDLE = "idle"
    STARTING = "starting"
    ACTIVE = "active"
    STOPPING = "stopping"
    ERROR = "error"


class GroupState(str, Enum):
    STOPPED = "stopped"
    STARTING = "starting"
    RUNNING = "running"
    READY = "ready"
    STOPPING = "stopping"
    EXITED = "exited"
    DEGRADED = "degraded"
    FAILED = "failed"


@dataclass(frozen=True)
class GroupSpec:
    name: str
    command: List[str]
    required_nodes: List[str] = field(default_factory=list)
    lifecycle_nodes: List[str] = field(default_factory=list)
    startup_timeout_sec: float = 45.0
    shutdown_timeout_sec: float = 8.0
    one_shot: bool = False


@dataclass(frozen=True)
class ModeSpec:
    mode: SystemMode
    groups: List[str]


@dataclass(frozen=True)
class ManagerConfig:
    environment: Mapping[str, str]
    groups: Mapping[str, GroupSpec]
    modes: Mapping[SystemMode, ModeSpec]
    health_strategies: List[str]
    graph_settle_timeout_sec: float = 3.0


@dataclass
class GroupHealth:
    state: GroupState
    ready: bool
    message: str
    missing_nodes: List[str] = field(default_factory=list)
    inactive_lifecycle_nodes: Dict[str, str] = field(default_factory=dict)
    exit_code: Optional[int] = None

    def to_dict(self) -> Dict[str, object]:
        return {
            "state": self.state.value,
            "ready": self.ready,
            "message": self.message,
            "missing_nodes": list(self.missing_nodes),
            "inactive_lifecycle_nodes": dict(self.inactive_lifecycle_nodes),
            "exit_code": self.exit_code,
        }
