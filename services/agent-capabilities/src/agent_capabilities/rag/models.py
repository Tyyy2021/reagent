from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints


def to_camel(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ContractModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="forbid")


class RagSearchRequest(ContractModel):
    contract_version: Literal[1]
    knowledge_base_id: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    index_version: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    query: Annotated[str, StringConstraints(min_length=1, max_length=512)]
    top_k: Annotated[int, Field(ge=1, le=5)]


class RagHit(ContractModel):
    chunk_id: Annotated[str, StringConstraints(min_length=1, max_length=255)]
    title: Annotated[str, StringConstraints(min_length=1, max_length=256)]
    section: Annotated[str, StringConstraints(min_length=1, max_length=256)]
    source: Annotated[str, StringConstraints(min_length=1, max_length=512)]
    score: Annotated[float, Field(ge=0.0, le=1.0)]
    excerpt: Annotated[str, StringConstraints(max_length=1200)]


class RagSearchResponse(ContractModel):
    contract_version: Literal[1]
    index_version: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    hits: Annotated[list[RagHit], Field(max_length=5)]


class ActiveIndexResponse(ContractModel):
    contract_version: Literal[1]
    knowledge_base_id: Literal["incident-ops"]
    index_version: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    ready: bool
