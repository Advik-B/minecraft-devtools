"""Capability (feature flag) checks: /v1/health lists them, and every capability that is off answers
501 feature-unavailable on its routes and hides its MCP tools. --capabilities / MCDT_EXPECT_CAPABILITIES
pins the exact set a node must report."""

from __future__ import annotations

import re
from typing import Any

import pytest

from conftest import Api, expect, problem

ALL = {
    "textures", "block-viewer", "world-stream", "screenshot", "packets", "input", "advancements", "recipes",
    "stats", "toasts", "bossbars", "subtitles", "offhand", "item-components", "chat-signing", "decompiler",
    "baritone", "config-screen",
}

# A representative route per gated capability. Baritone keeps its own problem type (see test_baritone.py).
ROUTES: dict[str, tuple[str, str, Any]] = {
    "textures": ("GET", "/v1/textures/atlases", None),
    "block-viewer": ("GET", "/v1/view/blocks/summary", None),
    "screenshot": ("GET", "/v1/screenshot", None),
    "packets": ("POST", "/v1/packets", {}),
    "input": ("POST", "/v1/input/keys", {}),
    "advancements": ("GET", "/v1/advancements", None),
    "recipes": ("GET", "/v1/recipes", None),
    "stats": ("GET", "/v1/stats", None),
    "decompiler": ("GET", "/v1/classes/java.lang.String/source", None),
}

MCP_TOOLS = {
    "screenshot": {"screenshot"},
    "textures": {"get_game_textures"},
    "block-viewer": {"view_blocks"},
    "decompiler": {"decompile_class"},
    "baritone": {"baritone_goto", "baritone_mine", "baritone_follow", "baritone_cancel"},
}


def test_health_lists_capabilities(api: Api, request: pytest.FixtureRequest) -> None:
    caps = expect(api.get("/v1/health"), 200)["capabilities"]
    assert caps == sorted(caps), caps
    assert all(re.fullmatch(r"[a-z]+(-[a-z]+)*", c) for c in caps), caps
    assert set(caps) <= ALL, f"unknown capability ids: {set(caps) - ALL}"

    baritone = request.config.getoption("--baritone")
    if baritone != "auto":
        assert ("baritone" in caps) == (baritone == "installed"), caps

    expected = request.config.getoption("--capabilities")
    if expected:
        want = {c.strip() for c in expected.split(",") if c.strip()} - {"baritone"}
        assert set(caps) - {"baritone"} == want


@pytest.mark.parametrize("capability", sorted(ROUTES))
def test_capability_off_is_feature_unavailable(api: Api, capabilities: set[str], capability: str) -> None:
    if capability in capabilities:
        pytest.skip(f"{capability} is available")
    method, path, body = ROUTES[capability]
    data = expect(api.request(method, path, json=body), 501, problem("feature-unavailable"))
    assert capability in data["detail"]


def test_mcp_hides_tools_without_capability(api: Api, capabilities: set[str]) -> None:
    tools = {t["name"] for t in api.mcp("tools/list", {})["result"]["tools"]}
    for capability, names in MCP_TOOLS.items():
        if capability not in capabilities:
            assert not names & tools, f"{capability} is off but {names & tools} are listed"
