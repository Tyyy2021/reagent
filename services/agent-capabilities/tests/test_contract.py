from pathlib import Path

import pytest
from pydantic import ValidationError

from agent_capabilities.rag.models import (
    ActiveIndexResponse,
    RagSearchRequest,
    RagSearchResponse,
)


def test_shared_rag_contract_examples() -> None:
    root = Path(__file__).parents[3] / "contracts"
    request = RagSearchRequest.model_validate_json(
        (root / "rag-search-v1.request.json").read_text(encoding="utf-8")
    )
    response = RagSearchResponse.model_validate_json(
        (root / "rag-search-v1.response.json").read_text(encoding="utf-8")
    )
    assert request.contract_version == 1
    assert response.index_version == request.index_version
    assert len(response.hits) == 1


def test_contract_models_reject_unknown_and_out_of_range_fields() -> None:
    with pytest.raises(ValidationError):
        RagSearchRequest.model_validate(
            {
                "contractVersion": 1,
                "knowledgeBaseId": "incident-ops",
                "indexVersion": "v1",
                "query": "checkout",
                "topK": 6,
                "unexpected": True,
            }
        )


def test_active_index_response_is_bounded_to_incident_ops() -> None:
    active = ActiveIndexResponse.model_validate(
        {
            "contractVersion": 1,
            "knowledgeBaseId": "incident-ops",
            "indexVersion": "v1-0123456789abcdef",
            "ready": False,
        }
    )

    assert active.knowledge_base_id == "incident-ops"
    with pytest.raises(ValidationError):
        ActiveIndexResponse.model_validate(
            {
                "contractVersion": 1,
                "knowledgeBaseId": "unknown",
                "indexVersion": "v1",
                "ready": False,
            }
        )
