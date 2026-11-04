"""MCDT must run with the libraries it bundles, not older copies the game ships (Minecraft 1.21.2+ ships Jackson 2.13
for msal4j). On NeoForge MCDT's Jackson is relocated under dev.advik.mcdt.shaded."""

from __future__ import annotations

import re
from typing import Any

from conftest import REPO, Api, expect

PACKAGE_VERSION = ("dev.advik.mcdt.shaded.jackson.databind.cfg.PackageVersion",
                   "com.fasterxml.jackson.databind.cfg.PackageVersion")


def _find(node: Any, key: str) -> Any:
    """First value stored under key anywhere in a decoded JSON tree."""
    if isinstance(node, dict):
        if key in node:
            return node[key]
        children = node.values()
    elif isinstance(node, list):
        children = node
    else:
        return None
    for child in children:
        found = _find(child, key)
        if found is not None:
            return found
    return None


def test_runs_bundled_jackson(api: Api) -> None:
    bundled = re.search(r"^jackson_version=(\S+)", (REPO / "gradle.properties").read_text(encoding="utf-8"), re.M).group(1)
    for name in PACKAGE_VERSION:
        response = api.get(f"/v1/classes/{name}")
        if response.status_code != 404:
            break
    version = expect(response, 200)["staticFields"]["VERSION"]
    loaded = ".".join(str(_find(version, k)) for k in ("_majorVersion", "_minorVersion", "_patchLevel"))
    assert loaded == bundled, f"{name} is {loaded}, but MCDT bundles Jackson {bundled}: {version}"
