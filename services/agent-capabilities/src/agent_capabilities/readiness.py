from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class CapabilityReadiness:
    rag_ready: bool
    rag_reason: str
    fake_ops_ready: bool
    fake_ops_reason: str

    @classmethod
    def initial(cls) -> "CapabilityReadiness":
        return cls(False, "index-not-initialized", False, "not-configured")

    def as_dict(self) -> dict[str, object]:
        return {
            "ready": self.rag_ready and self.fake_ops_ready,
            "service": "agent-capabilities",
            "rag": {"ready": self.rag_ready, "reason": self.rag_reason},
            "fakeOps": {"ready": self.fake_ops_ready, "reason": self.fake_ops_reason},
        }
