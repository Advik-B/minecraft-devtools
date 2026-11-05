"""Checks that need a loaded singleplayer world (--state=world)."""

from __future__ import annotations

import pytest

from conftest import Api, expect, skip_without

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


def test_block_models(api: Api, capabilities: set[str]) -> None:
    """The viewer's block models: geometry, render layer, tint and fluids. Model code differs most between versions.
    Zero and default values are left out of the JSON, hence the .get defaults."""
    skip_without(capabilities, "block-viewer")
    # State ids differ between versions; the low ones cover the blocks below, so look them up by state string.
    models = expect(api.post("/v1/world/block-models", {"ids": list(range(600))}), 200)["models"]
    by_state = {m["state"]: m for m in models}

    def first(prefix: str) -> dict:
        return next(m for state, m in by_state.items() if state == prefix or state.startswith(prefix + "["))

    stone = first("minecraft:stone")
    assert stone.get("layer", "solid") == "solid" and stone.get("occlusion") == 63 and len(stone["quads"]) == 6, stone
    assert all(len(q["pos"]) == 12 and len(q["uv"]) == 8 for q in stone["quads"])

    grass = by_state["minecraft:grass_block[snowy=false]"]
    assert any(q.get("tint", 0) == 1 for q in grass["quads"]), "grass_block has no grass-tinted quad"

    # Leaves are cutout with Fancy graphics but solid with Fast (CI), so check the layer on a sapling.
    sapling = first("minecraft:oak_sapling")
    assert sapling.get("layer") in ("cutout", "cutout_mipped"), sapling.get("layer")
    leaves = first("minecraft:oak_leaves")
    assert any(q.get("tint", 0) == 2 for q in leaves["quads"]), "oak_leaves has no foliage-tinted quad"

    water = first("minecraft:water")
    assert water["fluid"]["type"] == "water" and water["fluid"]["layer"] == "translucent", water["fluid"]
