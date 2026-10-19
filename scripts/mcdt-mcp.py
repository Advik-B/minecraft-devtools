#!/usr/bin/env python3
"""
MCDT Model Context Protocol (MCP) Stdio Shim
Relays standard input/output JSON-RPC 2.0 messages to the live Minecraft DevTools HTTP/SSE server.
"""

import sys
import os
import json
import glob
import urllib.request
import urllib.error
import threading
import argparse

def find_active_instance(target_port=None):
    candidates = []
    
    # 1. User home .mcdt/instances
    home = os.path.expanduser("~")
    user_instances = os.path.join(home, ".mcdt", "instances", "*.json")
    for f in glob.glob(user_instances):
        try:
            candidates.append((os.path.getmtime(f), f))
        except OSError:
            pass

    # 2. Local run/config/mcdt/instance.json
    local_inst = os.path.join(os.path.dirname(__file__), "..", "run", "config", "mcdt", "instance.json")
    if os.path.isfile(local_inst):
        try:
            candidates.append((os.path.getmtime(local_inst), local_inst))
        except OSError:
            pass

    candidates.sort(key=lambda x: x[0], reverse=True)

    for _, path in candidates:
        try:
            with open(path, "r", encoding="utf-8") as fp:
                data = json.load(fp)
            base_url = data.get("baseUrl") or data.get("url")
            token = data.get("token")
            port = data.get("port")
            if target_port and port != target_port:
                continue
            if base_url and token:
                # Test connectivity
                req = urllib.request.Request(f"{base_url}/v1/health")
                req.add_header("Authorization", f"Bearer {token}")
                try:
                    with urllib.request.urlopen(req, timeout=2.0) as resp:
                        if resp.status == 200:
                            return base_url, token, data
                except Exception:
                    continue
        except Exception:
            continue

    return None, None, None

def sse_listener(base_url, token, stop_event):
    """Background thread streaming SSE notifications from GET /mcp to stdout."""
    url = f"{base_url}/mcp"
    while not stop_event.is_set():
        try:
            req = urllib.request.Request(url)
            req.add_header("Authorization", f"Bearer {token}")
            req.add_header("Accept", "text/event-stream")
            with urllib.request.urlopen(req, timeout=60.0) as resp:
                current_event = None
                for line_bytes in resp:
                    if stop_event.is_set():
                        break
                    line = line_bytes.decode("utf-8", errors="replace").strip()
                    if not line:
                        current_event = None
                        continue
                    if line.startswith("event:"):
                        current_event = line[6:].strip()
                    elif line.startswith("data:"):
                        data_str = line[5:].strip()
                        if current_event == "message" and data_str:
                            sys.stdout.write(data_str + "\n")
                            sys.stdout.flush()
        except Exception:
            if not stop_event.is_set():
                stop_event.wait(2.0)

def main():
    parser = argparse.ArgumentParser(description="MCDT MCP Stdio Shim")
    parser.add_argument("--url", help="MCDT base URL (e.g. http://127.0.0.1:25580)")
    parser.add_argument("--token", help="Bearer token")
    parser.add_argument("--port", type=int, help="Target instance port")
    parser.add_argument("--no-sse", action="store_true", help="Disable background SSE notification listener")
    args = parser.parse_args()

    base_url = args.url
    token = args.token

    if not base_url or not token:
        base_url, token, info = find_active_instance(args.port)
        if not base_url or not token:
            sys.stderr.write("Error: No active MCDT instance found. Launch Minecraft with MCDT first.\n")
            sys.exit(1)

    stop_event = threading.Event()
    if not args.no_sse:
        sse_thread = threading.Thread(target=sse_listener, args=(base_url, token, stop_event), daemon=True)
        sse_thread.start()

    post_url = f"{base_url}/mcp"

    try:
        for line in sys.stdin:
            line = line.strip().lstrip('\ufeff')
            if not line:
                continue

            try:
                req_bytes = line.encode("utf-8")
                http_req = urllib.request.Request(post_url, data=req_bytes, method="POST")
                http_req.add_header("Authorization", f"Bearer {token}")
                http_req.add_header("Content-Type", "application/json")
                http_req.add_header("Accept", "application/json")

                with urllib.request.urlopen(http_req, timeout=10.0) as resp:
                    resp_bytes = resp.read()
                    if resp_bytes:
                        sys.stdout.write(resp_bytes.decode("utf-8") + "\n")
                        sys.stdout.flush()
            except urllib.error.HTTPError as e:
                err_body = e.read().decode("utf-8")
                sys.stdout.write(err_body + "\n")
                sys.stdout.flush()
            except Exception as e:
                err_rpc = {
                    "jsonrpc": "2.0",
                    "error": {"code": -32603, "message": str(e)},
                    "id": None
                }
                sys.stdout.write(json.dumps(err_rpc) + "\n")
                sys.stdout.flush()
    except (KeyboardInterrupt, BrokenPipeError):
        pass
    finally:
        stop_event.set()

if __name__ == "__main__":
    main()
