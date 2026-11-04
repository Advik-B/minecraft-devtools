"""Checks that need a loaded singleplayer world (--state=world)."""

from __future__ import annotations

import pytest

from conftest import Api, expect

pytestmark = pytest.mark.world


def test_player(api: Api) -> None:
    expect(api.get("/v1/player"), 200, lambda j: len(j["pos"]) == 3)


def test_player_inventory(api: Api) -> None:
    expect(api.get("/v1/player/inventory"), 200)


def test_world(api: Api) -> None:
    expect(api.get("/v1/world"), 200)


def test_world_blocks_below_player(api: Api) -> None:
    x, y, z = (int(v) for v in expect(api.get("/v1/player"), 200)["pos"])
    expect(api.get(f"/v1/world/blocks?from={x},{y - 2},{z}&to={x},{y - 1},{z}"), 200)


def test_hud(api: Api) -> None:
    expect(api.get("/v1/hud"), 200)


def test_objects_player_and_level(api: Api) -> None:
    expect(api.get("/v1/objects/player"), 200, lambda j: j["$handle"] == "player")
    expect(api.get("/v1/objects/level"), 200, lambda j: j["$handle"] == "level")
