"""Baritone live checks. With --baritone=installed they need a loaded world (--state=world);
with --baritone=absent every route must report 501 baritone-not-installed."""

from __future__ import annotations

import math
from typing import Any

import pytest

from conftest import Api, expect, problem, record_events, wait_for

pytestmark = pytest.mark.baritone

TERMINAL = ("completed", "failed", "cancelled")

ROUTES: list[tuple[str, str, Any]] = [
    ("GET", "/v1/baritone", None),
    ("GET", "/v1/baritone/status", None),
    ("GET", "/v1/baritone/path", None),
    ("POST", "/v1/baritone/goto", {"x": 0, "y": 64, "z": 0}),
    ("POST", "/v1/baritone/mine", {"blocks": ["minecraft:dirt"], "quantity": 1}),
    ("POST", "/v1/baritone/follow", {"name": "Nobody"}),
    ("POST", "/v1/baritone/explore", {"x": 0, "z": 0}),
    ("POST", "/v1/baritone/farm", {"range": 8}),
    ("POST", "/v1/baritone/build", {"name": "missing.schem"}),
    ("POST", "/v1/baritone/cancel", None),
    ("GET", "/v1/baritone/jobs/job_missing", None),
    ("GET", "/v1/baritone/settings", None),
    ("PATCH", "/v1/baritone/settings", {}),
    ("POST", "/v1/baritone/command", {"command": "stop"}),
]

MCP_TOOLS = {"baritone_goto", "baritone_mine", "baritone_follow", "baritone_cancel"}


@pytest.fixture(scope="session")
def baritone(api: Api, request: pytest.FixtureRequest) -> str:
    """'installed' or 'absent', from the server; fails if it contradicts --baritone."""
    response = api.get("/v1/baritone/status")
    actual = {200: "installed", 501: "absent"}.get(response.status_code)
    assert actual is not None, f"unexpected /v1/baritone/status: {response.status_code} {response.text[:300]}"
    wanted = request.config.getoption("--baritone")
    assert wanted in ("auto", actual), f"expected Baritone {wanted}, but the client reports it {actual}"
    return actual


def require(baritone: str, wanted: str) -> None:
    if baritone != wanted:
        pytest.skip(f"Baritone is {baritone}")


# ---- Baritone not installed ------------------------------------------------------------------------------------

@pytest.mark.parametrize("method,path,body", ROUTES, ids=[f"{m} {p}" for m, p, _ in ROUTES])
def test_absent_routes_report_not_installed(api: Api, baritone: str, method: str, path: str, body: Any) -> None:
    require(baritone, "absent")
    expect(api.request(method, path, json=body), 501, problem("baritone-not-installed"))


def test_absent_mcp_hides_and_rejects_tools(api: Api, baritone: str) -> None:
    require(baritone, "absent")
    names = {tool["name"] for tool in api.mcp("tools/list", {})["result"]["tools"]}
    assert not names & MCP_TOOLS, f"Baritone tools listed without Baritone: {names & MCP_TOOLS}"
    result = api.mcp("tools/call", {"name": "baritone_goto", "arguments": {"x": 0, "y": 64, "z": 0}})["result"]
    assert result["isError"] is True


# ---- Baritone installed, in a world ----------------------------------------------------------------------------

def player_pos(api: Api) -> list[float]:
    return expect(api.get("/v1/player"), 200)["pos"]


def job(api: Api, job_id: str) -> dict[str, Any]:
    return expect(api.get(f"/v1/baritone/jobs/{job_id}"), 200)


def start(api: Api, kind: str, body: Any) -> dict[str, Any]:
    return expect(api.post(f"/v1/baritone/{kind}", body), 202, lambda j: j["jobId"] and j["type"])


def cancel_and_wait(api: Api, job_id: str) -> dict[str, Any]:
    expect(api.post("/v1/baritone/cancel"), 200, lambda j: j["cancelled"] is True)
    final = wait_for(lambda: job(api, job_id), lambda j: j["status"] in TERMINAL, timeout=30)
    assert final["status"] == "cancelled", final
    return final


@pytest.mark.world
def test_status(api: Api, baritone: str) -> None:
    require(baritone, "installed")
    expect(api.get("/v1/baritone/status"), 200, lambda j: j["installed"] is True)
    expect(api.get("/v1/baritone"), 200, lambda j: j["installed"] is True)


@pytest.mark.world
def test_settings_round_trip(api: Api, baritone: str) -> None:
    require(baritone, "installed")
    settings = expect(api.get("/v1/baritone/settings"), 200)
    assert settings["allowBreak"]["type"].lower().startswith("bool"), settings["allowBreak"]
    original = settings["allowBreak"]["value"]
    try:
        expect(api.patch("/v1/baritone/settings", {"allowBreak": not original}), 200)
        expect(api.get("/v1/baritone/settings"), 200, lambda j: j["allowBreak"]["value"] is (not original))
    finally:
        expect(api.patch("/v1/baritone/settings", {"allowBreak": original}), 200)


@pytest.mark.world
def test_goto_walks_to_target(api: Api, baritone: str) -> None:
    require(baritone, "installed")
    x, y, z = (math.floor(v) for v in player_pos(api))
    target = (x + 6, y, z + 3)
    with record_events(api, ["baritone.path", "baritone.process"]) as events:
        started = start(api, "goto", {"x": target[0], "y": target[1], "z": target[2]})
        assert started["type"] == "goto"
        final = wait_for(lambda: job(api, started["jobId"]), lambda j: j["status"] in TERMINAL, timeout=120)
    path_events = [e["data"] for e in events if e.get("topic", "").startswith("baritone.")]
    assert final["status"] == "completed", f"{final}; Baritone events: {path_events}"

    px, py, pz = player_pos(api)
    distance = math.dist((px, pz), (target[0] + 0.5, target[2] + 0.5))
    assert distance < 1.5, f"player at {(px, py, pz)}, target {target}, distance {distance:.2f}"
    expect(api.get("/v1/baritone/path"), 200, lambda j: isinstance(j["hasPath"], bool))


@pytest.mark.world
@pytest.mark.parametrize("kind,body", [
    ("mine", {"blocks": ["minecraft:dirt"], "quantity": 1}),
    ("explore", {"x": 200, "z": 200}),
    ("farm", {"range": 8}),
], ids=["mine", "explore", "farm"])
def test_process_starts_and_cancels(api: Api, baritone: str, kind: str, body: Any) -> None:
    require(baritone, "installed")
    started = start(api, kind, body)
    cancel_and_wait(api, started["jobId"])


@pytest.mark.world
def test_follow_unknown_target_is_not_a_server_error(api: Api, baritone: str) -> None:
    require(baritone, "installed")
    response = api.post("/v1/baritone/follow", {"name": "NoSuchPlayer"})
    assert response.status_code < 500, response.text[:300]
    api.post("/v1/baritone/cancel")


@pytest.mark.world
def test_build_missing_schematic_is_a_client_error(api: Api, baritone: str) -> None:
    require(baritone, "installed")
    response = api.post("/v1/baritone/build", {"name": "does-not-exist.schem"})
    assert 400 <= response.status_code < 500, f"{response.status_code}: {response.text[:300]}"


@pytest.mark.world
def test_command(api: Api, baritone: str) -> None:
    require(baritone, "installed")
    expect(api.post("/v1/baritone/command", {"command": "stop"}), 200, lambda j: j["success"] is True)


@pytest.mark.world
def test_unknown_job_is_404(api: Api, baritone: str) -> None:
    require(baritone, "installed")
    expect(api.get("/v1/baritone/jobs/job_missing"), 404)


@pytest.mark.world
def test_mcp_tools(api: Api, baritone: str) -> None:
    require(baritone, "installed")
    names = {tool["name"] for tool in api.mcp("tools/list", {})["result"]["tools"]}
    assert MCP_TOOLS <= names, f"missing Baritone MCP tools: {MCP_TOOLS - names}"

    x, y, z = (math.floor(v) for v in player_pos(api))
    result = api.mcp("tools/call", {"name": "baritone_goto", "arguments": {"x": x - 4, "y": y, "z": z}})["result"]
    assert result["isError"] is False, result
    assert "job_" in result["content"][0]["text"]
    cancelled = api.mcp("tools/call", {"name": "baritone_cancel", "arguments": {}})["result"]
    assert cancelled["isError"] is False, cancelled
