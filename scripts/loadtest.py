#!/usr/bin/env python3
"""
CodeArena load test.

Drives the running compose stack through the paths that actually cost something and
reports throughput, latency percentiles, error rate and queue behaviour.

    python scripts/loadtest.py                     # the default scenario set
    python scripts/loadtest.py --users 30 --duration 60
    python scripts/loadtest.py --scenario submissions

WHAT THIS IS AND IS NOT

It is a reproducible, bounded measurement of a deployment on one machine. Each scenario
runs a fixed number of workers for a fixed time against a stack whose resource ceilings
are declared in docker-compose.yml, so two runs on the same hardware are comparable and
a regression is visible.

It is not a capacity model for a cluster, and it does not ramp to infinity. Unbounded
load against a system with rate limiting and a bounded queue measures the rate limiter,
which is already tested directly; what is useful here is behaviour under load a real
deployment would actually see.

RATE LIMITING IS NOT AN ERROR

429 is counted in its own column, never as a failure. A 429 under load is the system
working: the limiter shedding load before the judge queue fills. Folding it into an error
rate would report correct backpressure as a fault and hide real failures underneath it.
"""

from __future__ import annotations

import argparse
import json
import random
import string
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter
from dataclasses import dataclass, field
from http.cookiejar import CookieJar

DEFAULT_API = "http://localhost:8080"
PASSWORD = "load-test-passphrase-9f2c"


# --------------------------------------------------------------------------- client


class Client:
    """A browser-shaped HTTP client: one cookie jar, CSRF echoed back as a header."""

    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.jar = CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar)
        )

    def _csrf(self) -> str:
        for cookie in self.jar:
            if cookie.name == "XSRF-TOKEN":
                return cookie.value
        return ""

    def request(self, method: str, path: str, body: dict | None = None):
        """Returns (status, elapsed_seconds, parsed_body_or_None)."""
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(self.base + path, data=data, method=method)
        request.add_header("Content-Type", "application/json")
        request.add_header("Accept", "application/json")
        token = self._csrf()
        if token:
            request.add_header("X-XSRF-TOKEN", token)

        started = time.perf_counter()
        try:
            with self.opener.open(request, timeout=30) as response:
                payload = response.read()
                elapsed = time.perf_counter() - started
                try:
                    return response.status, elapsed, json.loads(payload)
                except (ValueError, TypeError):
                    return response.status, elapsed, None
        except urllib.error.HTTPError as e:
            elapsed = time.perf_counter() - started
            try:
                return e.code, elapsed, json.loads(e.read())
            except (ValueError, TypeError):
                return e.code, elapsed, None
        except Exception:                                   # noqa: BLE001
            # A connection reset or a timeout. Counted as a failure, which it is.
            return 0, time.perf_counter() - started, None

    def prime(self):
        """Fetch the public endpoint once, so the jar holds a CSRF token."""
        self.request("GET", "/api/system/info")


# --------------------------------------------------------------------------- results


@dataclass
class Results:
    name: str
    # Statuses this scenario is trying to produce. Counting them as failures would report
    # the system working as the system broken: a failed-login scenario succeeds by being
    # answered 401.
    expected: frozenset = frozenset({200, 201, 202, 204})
    latencies: list[float] = field(default_factory=list)
    statuses: Counter = field(default_factory=Counter)
    started_at: float = 0.0
    ended_at: float = 0.0
    lock: threading.Lock = field(default_factory=threading.Lock)

    def record(self, status: int, elapsed: float):
        with self.lock:
            self.latencies.append(elapsed)
            self.statuses[status] += 1

    @property
    def total(self) -> int:
        return sum(self.statuses.values())

    @property
    def rate_limited(self) -> int:
        return self.statuses.get(429, 0)

    @property
    def failed(self) -> int:
        """Transport failures, 5xx, and any 4xx this scenario was not trying to provoke."""
        return sum(
            count
            for status, count in self.statuses.items()
            if status == 0
            or status >= 500
            or (400 <= status < 500 and status != 429 and status not in self.expected)
        )

    def percentile(self, p: float) -> float:
        if not self.latencies:
            return 0.0
        ordered = sorted(self.latencies)
        index = min(len(ordered) - 1, int(round((p / 100) * len(ordered)) - 1))
        return ordered[max(0, index)]

    def report(self) -> str:
        duration = max(self.ended_at - self.started_at, 1e-9)
        throughput = self.total / duration
        succeeded = self.total - self.failed - self.rate_limited
        codes = " ".join(f"{s}:{c}" for s, c in sorted(self.statuses.items()))
        return (
            f"  {self.name:<22} {self.total:>6} reqs  {throughput:>7.1f}/s   "
            f"ok {succeeded:>5}  429 {self.rate_limited:>5}  fail {self.failed:>4}\n"
            f"  {'':<22} p50 {self.percentile(50) * 1000:>7.1f}ms  "
            f"p95 {self.percentile(95) * 1000:>7.1f}ms  "
            f"p99 {self.percentile(99) * 1000:>7.1f}ms  "
            f"max {max(self.latencies or [0]) * 1000:>7.1f}ms\n"
            f"  {'':<22} statuses: {codes}"
        )


# --------------------------------------------------------------------------- setup


def random_suffix(n: int = 8) -> str:
    return "".join(random.choices(string.ascii_lowercase + string.digits, k=n))


def make_accounts(api: str, count: int, run_id: str) -> list[Client]:
    """
    Registers and signs in the load users.

    Registration is rate limited per client, so this deliberately runs against the limit
    and keeps whichever accounts it got. A load test that demanded N accounts and failed
    when the limiter did its job would be testing the wrong thing.
    """
    clients: list[Client] = []
    for i in range(count):
        client = Client(api)
        client.prime()
        username = f"lt{run_id}{i}"
        status, _, _ = client.request(
            "POST",
            "/api/auth/register",
            {"username": username, "email": f"{username}@loadtest.local", "password": PASSWORD},
        )
        if status not in (201, 409):
            continue
        status, _, _ = client.request(
            "POST", "/api/auth/login", {"identifier": username, "password": PASSWORD}
        )
        if status == 200:
            clients.append(client)
    return clients


def find_published_problem(client: Client) -> str | None:
    status, _, body = client.request("GET", "/api/problems?size=1")
    if status != 200 or not body or not body.get("items"):
        return None
    return body["items"][0]["id"]


# --------------------------------------------------------------------------- scenarios


def run_scenario(name: str, clients: list[Client], action, duration: float,
                 pace: float = 0.0, expected: frozenset | None = None) -> Results:
    """
    Runs `action(client)` on every client in parallel until the duration elapses.

    `pace` is a per-request think time. Zero means "as fast as this client can go", which
    is the right setting for measuring a ceiling and the wrong one for imitating a user;
    the browsing scenarios use a pace for that reason.
    """
    results = Results(name, expected or frozenset({200, 201, 202, 204}))
    stop_at = time.perf_counter() + duration
    barrier = threading.Barrier(len(clients) + 1)

    def worker(client: Client):
        barrier.wait()
        while time.perf_counter() < stop_at:
            status, elapsed = action(client)
            results.record(status, elapsed)
            if pace:
                time.sleep(pace)

    threads = [threading.Thread(target=worker, args=(c,), daemon=True) for c in clients]
    for thread in threads:
        thread.start()

    barrier.wait()
    results.started_at = time.perf_counter()
    for thread in threads:
        thread.join(timeout=duration + 60)
    results.ended_at = time.perf_counter()
    return results


def browse_problems(client: Client):
    status, elapsed, _ = client.request("GET", "/api/problems?size=20")
    return status, elapsed


def search_problems(client: Client):
    status, elapsed, _ = client.request("GET", "/api/problems?search=a&size=20")
    return status, elapsed


def read_history(client: Client):
    status, elapsed, _ = client.request("GET", "/api/submissions?size=20")
    return status, elapsed


def login_repeatedly(api: str):
    def action(_client: Client):
        fresh = Client(api)
        fresh.prime()
        status, elapsed, _ = fresh.request(
            "POST", "/api/auth/login", {"identifier": "nobody-here", "password": "wrong"}
        )
        return status, elapsed
    return action


def submit(problem_id: str):
    def action(client: Client):
        status, elapsed, _ = client.request(
            "POST",
            f"/api/problems/{problem_id}/submissions",
            {"language": "PYTHON", "sourceCode": "print(1)"},
        )
        return status, elapsed
    return action


# --------------------------------------------------------------------------- main


def main() -> int:
    parser = argparse.ArgumentParser(description="CodeArena load test")
    parser.add_argument("--api", default=DEFAULT_API)
    parser.add_argument("--users", type=int, default=20,
                        help="concurrent clients per scenario")
    parser.add_argument("--duration", type=float, default=20.0,
                        help="seconds per scenario")
    parser.add_argument("--scenario", default="all",
                        choices=["all", "browse", "search", "history", "login", "submissions"])
    args = parser.parse_args()

    run_id = random_suffix(6)
    print(f"CodeArena load test   api={args.api} users={args.users} "
          f"duration={args.duration}s run={run_id}")
    print("-" * 78)

    print("preparing accounts...", flush=True)
    clients = make_accounts(args.api, args.users, run_id)
    if not clients:
        print("ERROR: could not sign in any load users. Is the stack running?",
              file=sys.stderr)
        return 1
    print(f"  {len(clients)} of {args.users} signed in "
          f"({args.users - len(clients)} refused, most likely by the registration limit)")

    problem_id = find_published_problem(clients[0])
    if problem_id is None:
        print("  no published problem found: skipping the submission scenario")

    ok = frozenset({200, 201, 202, 204})
    scenarios = []
    if args.scenario in ("all", "browse"):
        scenarios.append(("browse problems", browse_problems, 0.05, ok))
    if args.scenario in ("all", "search"):
        scenarios.append(("search problems", search_problems, 0.05, ok))
    if args.scenario in ("all", "history"):
        scenarios.append(("submission history", read_history, 0.05, ok))
    if args.scenario in ("all", "login"):
        # 401 is what this scenario is for: it drives the login limiter with credentials
        # that are meant to be rejected.
        scenarios.append(("failed logins", login_repeatedly(args.api), 0.0, frozenset({401})))
    if args.scenario in ("all", "submissions") and problem_id:
        scenarios.append(("submissions", submit(problem_id), 0.0, ok))

    print()
    reports = []
    for name, action, pace, expected in scenarios:
        print(f"running: {name}", flush=True)
        results = run_scenario(name, clients, action, args.duration, pace, expected)
        reports.append(results)
        print(results.report())
        print()

    print("-" * 78)
    print("summary")
    for results in reports:
        print(results.report())

    print("-" * 78)
    print("Note: queue depth and worker state are on /api/admin/system/status "
          "(administrator only).")

    failures = sum(r.failed for r in reports)
    print(f"\ntotal failures (excluding 429): {failures}")
    return 0 if failures == 0 else 2


if __name__ == "__main__":
    sys.exit(main())
