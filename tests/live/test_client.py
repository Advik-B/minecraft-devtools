"""Port of scripts/test-live-client.ps1: the title-screen suite plus MCP checks."""

from __future__ import annotations

import subprocess
import sys
from dataclasses import dataclass, field
from typing import Any, Callable

import pytest

from conftest import REPO, Api, expect, problem, record_events, wait_for


@dataclass(frozen=True)
class Case:
    name: str
    path: str
    method: str = "GET"
    status: int = 200
    body: Any = None
    auth: bool = True
    headers: dict[str, str] = field(default_factory=dict)
    check: Callable[[Any], Any] | None = None
    raw: Callable[[str], Any] | None = None


def run(api: Api, case: Case) -> Any:
    response = api.request(case.method, case.path, json=case.body, auth=case.auth, headers=case.headers)
    data = expect(response, case.status, case.check)
    if case.raw is not None:
        assert case.raw(response.text), f"{case.name}: body check failed: {response.text[:300]}"
    return data


def cases(*items: Case) -> list[Any]:
    return [pytest.param(c, id=c.name) for c in items]


SYSTEM = cases(
    Case("health", "/v1/health", check=lambda j: j["mcdtVersion"] is not None and j["uptimeMs"] >= 0 and j["tick"] >= 0),
    Case("unauthorized rejected", "/v1/health", auth=False, status=401),
    Case("openapi 3.1", "/openapi.json", auth=False, check=lambda j: j["openapi"].startswith("3.1")),
    Case("webui root", "/", auth=False, raw=lambda t: "scalar" in t or "data-theme" in t or "MCDT" in t),
    Case("scalar /docs", "/docs", auth=False, raw=lambda t: "scalar" in t),
    Case("scalar standalone js", "/docs/scalar/standalone.js", auth=False, raw=lambda t: len(t) > 1000),
    Case("redoc", "/redoc", auth=False, raw=lambda t: "redoc" in t),
)

CLIENT = cases(
    Case("client state", "/v1/client", check=lambda j: j["fps"] >= 0 and j["window"] is not None),
    Case("options", "/v1/options", check=lambda j: j["fov"] > 0 and j["renderDistance"] > 0),
    Case("options patch", "/v1/options", method="PATCH", body={"fov": 85, "renderDistance": 12},
         check=lambda j: j["fov"] == 85 and j["renderDistance"] == 12),
    Case("screenshot", "/v1/screenshot?scale=0.2", raw=lambda t: len(t) > 50),
    Case("screenshot bad scale", "/v1/screenshot?scale=0.01", status=400, check=problem("invalid-parameter")),
    Case("registries", "/v1/registries", check=lambda j: len(j["registries"]) > 30),
    Case("item registry query", "/v1/registries/minecraft:item?limit=5&query=diamond", check=lambda j: len(j["entries"]) > 0),
    Case("unknown registry", "/v1/registries/minecraft:unknown", status=404, check=problem("registry-not-found")),
    Case("camera", "/v1/camera", check=lambda j: j["perspective"] is not None and j["fov"] > 0),
    Case("window", "/v1/window", check=lambda j: j["width"] > 0 and j["height"] > 0),
    Case("debug", "/v1/debug", check=lambda j: j["f3Left"] is not None),
    Case("keybinds", "/v1/keybinds", check=lambda j: j["total"] > 0 and len(j["keybinds"]) > 0),
    Case("resource packs", "/v1/resourcepacks", check=lambda j: j["available"] is not None and j["enabled"] is not None),
    Case("session", "/v1/session", check=lambda j: j["state"] is not None),
    Case("worlds", "/v1/worlds", check=lambda j: j["total"] >= 0 and j["worlds"] is not None),
    Case("servers", "/v1/servers", check=lambda j: j["total"] >= 0 and j["servers"] is not None),
    Case("batch read", "/v1/batch", method="POST",
         body={"requests": [{"id": "client", "path": "/v1/client"}, {"id": "session", "path": "/v1/session"}]},
         check=lambda j: j["results"]["client"]["status"] == 200 and j["results"]["session"]["status"] == 200),
)

REFLECTION = cases(
    Case("root handles", "/v1/objects/roots",
         check=lambda j: all(j["roots"].get(k) is not None for k in ("minecraft", "options", "gameRenderer"))),
    Case("inspect minecraft", "/v1/objects/minecraft",
         check=lambda j: j["$handle"] == "minecraft" and j["$class"] is not None and j["fields"] is not None),
    Case("inspect options", "/v1/objects/options", check=lambda j: j["$handle"] == "options" and j["fields"] is not None),
    Case("inspect gameRenderer", "/v1/objects/gameRenderer",
         check=lambda j: j["$handle"] == "gameRenderer" and j["fields"] is not None),
    Case("path renderDistance", "/v1/objects/minecraft?path=options.renderDistance().get()",
         check=lambda j: j["path"] == "options.renderDistance().get()" and j["value"] is not None),
    Case("path fps", "/v1/objects/minecraft?path=fps", check=lambda j: j["path"] == "fps" and j["value"] is not None),
    Case("invalid path", "/v1/objects/minecraft?path=invalid.fakeField", status=400, check=problem("path-evaluation-failed")),
    Case("missing field", "/v1/objects/minecraft/fields/nonExistent", status=404, check=problem("field-not-found")),
    Case("static class Minecraft", "/v1/classes/net.minecraft.client.Minecraft",
         check=lambda j: j["$class"] == "net.minecraft.client.Minecraft" and j["staticMethods"] is not None),
    Case("static class String", "/v1/classes/java.lang.String",
         check=lambda j: j["$class"] == "java.lang.String" and j["staticMethods"] is not None),
    Case("unknown class", "/v1/classes/com.nonexistent.NoSuchClass", status=404, check=problem("class-not-found")),
    Case("denylist Runtime", "/v1/classes/java.lang.Runtime", status=403, check=problem("reflection-denied")),
    Case("denylist ClassLoader", "/v1/classes/java.lang.ClassLoader", status=403, check=problem("reflection-denied")),
    Case("cannot release root handle", "/v1/objects/minecraft", method="DELETE", status=400),
)

DECOMPILER = cases(
    Case("decompile minecraft remapped", "/v1/classes/net.minecraft.client.Minecraft/source?remap=true",
         check=lambda j: j["$class"] == "net.minecraft.client.Minecraft" and "class Minecraft" in j["source"] and j["remapped"] is True),
    Case("decompile minecraft cached", "/v1/classes/net.minecraft.client.Minecraft/source?remap=true",
         check=lambda j: j["cached"] is True and j["durationMs"] == 0),
    Case("decompile mod class", "/v1/classes/dev.advik.mcdt.Mcdt/source",
         check=lambda j: j["$class"] == "dev.advik.mcdt.Mcdt" and "class Mcdt" in j["source"]),
    Case("decompile raw bytecode", "/v1/classes/net.minecraft.client.Minecraft/source?remap=false",
         check=lambda j: j["remapped"] is False),
    Case("decompile plain text", "/v1/classes/dev.advik.mcdt.Mcdt/source?format=text", headers={"Accept": "text/plain"},
         raw=lambda t: "class Mcdt" in t),
    Case("decompile denylist", "/v1/classes/java.lang.Runtime/source", status=403, check=problem("reflection-denied")),
)

INPUT = cases(
    Case("key tap escape", "/v1/input/keys", method="POST", body={"key": "escape", "action": "tap"},
         check=lambda j: j["action"] == "tap"),
    Case("mouse move", "/v1/input/mouse", method="POST", body={"action": "move", "x": 320.0, "y": 240.0},
         check=lambda j: j["action"] == "move"),
    Case("chat history", "/v1/chat?limit=10", check=lambda j: j["messages"] is not None),
)

# The title screen: no player, no world.
MENU = cases(
    Case("screen root is a Screen", "/v1/objects/screen", check=lambda j: j["$handle"] == "screen" and "Screen" in j["$class"]),
    Case("player root", "/v1/objects/player", status=409, check=problem("no-player")),
    Case("level root", "/v1/objects/level", status=409, check=problem("no-world")),
    Case("screen widget tree", "/v1/screen", check=lambda j: j["open"] is True),
    Case("container slots on title screen", "/v1/screen/slots", method="POST", body={"slot": 0, "mode": "pickup", "button": 0},
         status=409, check=problem("no-container")),
    Case("player", "/v1/player", status=409, check=problem("no-player")),
    Case("player inventory", "/v1/player/inventory", status=409, check=problem("no-player")),
    Case("player action", "/v1/player/actions", method="POST", body={"action": "attack"}, status=409, check=problem("no-player")),
    Case("player look", "/v1/player/look", method="POST", body={"yaw": 90.0, "pitch": 0.0}, status=409, check=problem("no-player")),
    Case("player move", "/v1/player/move", method="POST", body={"forward": True}, status=409, check=problem("no-player")),
    Case("world", "/v1/world", status=409, check=problem("no-world")),
    Case("world blocks", "/v1/world/blocks?from=0,64,0&to=1,65,1", status=409, check=problem("no-world")),
    Case("world entities", "/v1/world/entities", status=409, check=problem("no-world")),
    Case("world raycast", "/v1/world/raycast", status=409, check=problem("no-world")),
    Case("chat send", "/v1/chat", method="POST", body={"message": "hello"}, status=409, check=problem("no-player")),
    Case("hud", "/v1/hud", status=409, check=problem("no-player")),
    Case("view blocks", "/v1/view/blocks", status=409, check=problem("no-world")),
    Case("stats", "/v1/stats", status=409, check=problem("no-player")),
    Case("advancements", "/v1/advancements", status=409, check=problem("no-player")),
    Case("recipes", "/v1/recipes", status=409, check=problem("no-player")),
)


@pytest.mark.parametrize("case", SYSTEM)
def test_system(api: Api, case: Case) -> None:
    run(api, case)


@pytest.mark.parametrize("case", CLIENT)
def test_client(api: Api, case: Case) -> None:
    run(api, case)


@pytest.mark.parametrize("case", REFLECTION)
def test_reflection(api: Api, case: Case) -> None:
    run(api, case)


@pytest.mark.parametrize("case", DECOMPILER)
def test_decompiler(api: Api, case: Case) -> None:
    run(api, case)


@pytest.mark.parametrize("case", INPUT)
def test_input(api: Api, case: Case) -> None:
    run(api, case)


@pytest.mark.menu
@pytest.mark.parametrize("case", MENU)
def test_menu_state(api: Api, case: Case) -> None:
    run(api, case)


def test_handle_release(api: Api) -> None:
    expanded = expect(api.get("/v1/objects/minecraft/fields/options?depth=1"), 200,
                      lambda j: j["field"] == "options" and j["value"]["$handle"] is not None)
    handle = expanded["value"]["$handle"]
    expect(api.delete(f"/v1/objects/{handle}"), 200, lambda j: j["released"] is True)
    expect(api.get(f"/v1/objects/{handle}"), 404)


def test_mcp(api: Api) -> None:
    init = api.mcp("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                  "clientInfo": {"name": "LiveTestClient", "version": "1.0.0"}})
    assert init["result"]["protocolVersion"] == "2024-11-05" and init["result"]["serverInfo"]["name"] == "mcdt"
    assert api.mcp("ping")["result"] is not None
    assert len(api.mcp("tools/list", {})["result"]["tools"]) >= 5

    state = api.mcp("tools/call", {"name": "get_state", "arguments": {}})["result"]
    assert state["isError"] is False and "client" in state["content"][0]["text"]

    shot = api.mcp("tools/call", {"name": "screenshot", "arguments": {"scale": 0.2}})["result"]
    assert shot["isError"] is False and shot["content"][0]["type"] == "image" and len(shot["content"][0]["data"]) > 100

    assert len(api.mcp("resources/list", {})["result"]["resources"]) >= 5
    assert api.mcp("resources/read", {"uri": "mcdt://screen"})["result"]["contents"][0]["uri"] == "mcdt://screen"

    expect(api.get("/v1/mcp/status"), 200,
           lambda j: j["enabled"] is True and j["protocolVersion"] == "2024-11-05" and j["registeredTools"] >= 5)
    expect(api.get("/v1/mcp/log"), 200, lambda j: len(j) >= 2)


def test_events_stream_delivers_ticks(api: Api) -> None:
    with record_events(api, ["tick"]) as events:
        wait_for(lambda: len([e for e in events if e.get("topic") == "tick"]), lambda n: n >= 3, timeout=15, interval=0.2)
    ticks = [e for e in events if e.get("topic") == "tick"]
    assert len(ticks) >= 3, f"expected tick events, got {events[:5]}"


def test_mcp_stdio_shim(api: Api) -> None:
    result = subprocess.run(
        [sys.executable, str(REPO / "scripts" / "mcdt-mcp.py"), "--url", api.base_url, "--token", api.token, "--no-sse"],
        input='{"jsonrpc":"2.0","id":99,"method":"ping"}\n', capture_output=True, text=True, timeout=60)
    assert '"id":99' in result.stdout.replace(" ", ""), f"shim output: {result.stdout!r} stderr: {result.stderr[-500:]!r}"


@pytest.mark.shutdown
def test_quit(api: Api) -> None:
    step1 = expect(api.post("/v1/session/quit"), 202, lambda j: j["confirmToken"] is not None)
    expect(api.post("/v1/session/quit/confirm", {"confirmToken": step1["confirmToken"]}), 200,
           lambda j: j["status"] == "shutting_down")
