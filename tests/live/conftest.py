"""Shared fixtures for the MCDT live suite.

Run against a running client:

    uv run --project tests/live pytest tests/live

The client is found from MCDT_URL / MCDT_TOKEN, --mcdt-url / --mcdt-token, the newest
~/.mcdt/instances/*.json discovery file, or run/config/mcdt/instance.json (dev runs).
"""

from __future__ import annotations

import json
import os
import pathlib
import threading
import time
from contextlib import contextmanager
from typing import Any, Callable, Iterator

import httpx
import pytest
from websockets.sync.client import connect

REPO = pathlib.Path(__file__).resolve().parents[2]


def pytest_addoption(parser: pytest.Parser) -> None:
    group = parser.getgroup("mcdt")
    group.addoption("--mcdt-url", default=os.environ.get("MCDT_URL"), help="base URL, e.g. http://127.0.0.1:25580")
    group.addoption("--mcdt-token", default=os.environ.get("MCDT_TOKEN"), help="bearer token")
    group.addoption("--state", choices=("menu", "world"), default="menu",
                    help="client state the suite expects: title screen or a loaded world (default: menu)")
    group.addoption("--baritone", choices=("auto", "installed", "absent"), default="auto",
                    help="whether Baritone must be installed; auto accepts either (default: auto)")
    group.addoption("--shutdown", action="store_true", help="finish with the two-step quit flow")
    group.addoption("--capabilities", default=os.environ.get("MCDT_EXPECT_CAPABILITIES"),
                    help="comma-separated capability ids the platform must report exactly (baritone is checked by --baritone); "
                         "unset checks consistency only")


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    state = config.getoption("--state")
    for item in items:
        if "menu" in item.keywords and state != "menu":
            item.add_marker(pytest.mark.skip(reason="needs the title screen (--state=menu)"))
        if "world" in item.keywords and state != "world":
            item.add_marker(pytest.mark.skip(reason="needs a loaded world (--state=world)"))
        if "shutdown" in item.keywords and not config.getoption("--shutdown"):
            item.add_marker(pytest.mark.skip(reason="quits the client; pass --shutdown"))
    # The quit flow ends the client, so it always runs last.
    items.sort(key=lambda item: "shutdown" in item.keywords)


def _discover() -> tuple[str | None, str | None]:
    instances = pathlib.Path.home() / ".mcdt" / "instances"
    candidates = sorted(instances.glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True) if instances.is_dir() else []
    candidates.append(REPO / "run" / "config" / "mcdt" / "instance.json")
    for path in candidates:
        if path.is_file():
            data = json.loads(path.read_text(encoding="utf-8"))
            return data.get("baseUrl") or data.get("url"), data.get("token")
    return None, None


class Api:
    """Thin HTTP client for the MCDT API. Every call sends the bearer token unless auth=False."""

    def __init__(self, base_url: str, token: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.http = httpx.Client(base_url=self.base_url, timeout=60.0, follow_redirects=True)
        self._rpc_id = 0

    def request(self, method: str, path: str, *, json: Any = None, auth: bool = True,
                headers: dict[str, str] | None = None) -> httpx.Response:
        all_headers = {"Authorization": f"Bearer {self.token}"} if auth else {}
        all_headers.update(headers or {})
        return self.http.request(method, path, json=json, headers=all_headers)

    def get(self, path: str, **kw: Any) -> httpx.Response:
        return self.request("GET", path, **kw)

    def post(self, path: str, body: Any = None, **kw: Any) -> httpx.Response:
        return self.request("POST", path, json=body, **kw)

    def patch(self, path: str, body: Any = None, **kw: Any) -> httpx.Response:
        return self.request("PATCH", path, json=body, **kw)

    def delete(self, path: str, **kw: Any) -> httpx.Response:
        return self.request("DELETE", path, **kw)

    def mcp(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        """Sends one JSON-RPC request to /mcp and returns the decoded response."""
        self._rpc_id += 1
        body: dict[str, Any] = {"jsonrpc": "2.0", "id": self._rpc_id, "method": method}
        if params is not None:
            body["params"] = params
        response = self.post("/mcp", body)
        expect(response, 200)
        return response.json()


def expect(response: httpx.Response, status: int, check: Callable[[Any], Any] | None = None) -> Any:
    """Asserts the status code, then that check(json) is truthy. Returns the decoded JSON (or None)."""
    body = response.text
    assert response.status_code == status, (
        f"{response.request.method} {response.request.url.raw_path.decode()} -> {response.status_code}, "
        f"expected {status}: {body[:1500]}")
    data = None
    if body[:1] in ("{", "["):
        data = response.json()
    if check is not None:
        assert check(data), f"check failed for {response.request.url.raw_path.decode()}: {body[:1500]}"
    return data


def problem(type_: str) -> Callable[[Any], bool]:
    """Check for an RFC 9457 problem body with the given type."""
    return lambda j: j is not None and j.get("type") == type_


def wait_for(fetch: Callable[[], Any], ok: Callable[[Any], bool], timeout: float, interval: float = 1.0) -> Any:
    """Polls fetch() until ok(result) or the timeout; returns the last result."""
    deadline = time.monotonic() + timeout
    result = fetch()
    while not ok(result) and time.monotonic() < deadline:
        time.sleep(interval)
        result = fetch()
    return result


@contextmanager
def record_events(api: Api, topics: list[str]) -> Iterator[list[dict[str, Any]]]:
    """Subscribes to /v1/events and collects every message received while the block runs."""
    url = "ws" + api.base_url[len("http"):] + "/v1/events"
    received: list[dict[str, Any]] = []
    stop = threading.Event()
    with connect(url, additional_headers={"Authorization": f"Bearer {api.token}"}, open_timeout=10) as ws:
        ws.send(json.dumps({"action": "subscribe", "topics": topics}))

        def pump() -> None:
            while not stop.is_set():
                try:
                    message = ws.recv(timeout=0.25)
                except TimeoutError:
                    continue
                except Exception:
                    return
                received.append(json.loads(message))

        thread = threading.Thread(target=pump, daemon=True)
        thread.start()
        try:
            yield received
        finally:
            stop.set()
            thread.join(timeout=2)


@pytest.fixture(scope="session")
def api(request: pytest.FixtureRequest) -> Api:
    url = request.config.getoption("--mcdt-url")
    token = request.config.getoption("--mcdt-token")
    if not url or not token:
        found_url, found_token = _discover()
        url, token = url or found_url, token or found_token
    if not url or not token:
        pytest.exit("No running MCDT instance found. Start the client (./gradlew runClient) or pass --mcdt-url/--mcdt-token.", 2)
    client = Api(url, token)
    yield client
    client.http.close()


@pytest.fixture(scope="session")
def capabilities(api: Api) -> set[str]:
    """Capability ids the running client reports in /v1/health."""
    return set(expect(api.get("/v1/health"), 200)["capabilities"])
