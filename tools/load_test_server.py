import argparse
import asyncio
import json
import math
from pathlib import Path
import statistics
import time
from dataclasses import dataclass

import httpx


@dataclass
class Scenario:
    name: str
    method: str
    path: str
    headers: dict | None = None
    params: dict | None = None
    json_body: dict | None = None
    data: dict | None = None
    files: dict | None = None


def percentile(values: list[float], p: float) -> float:
    if not values:
        return math.nan
    if len(values) == 1:
        return values[0]
    values = sorted(values)
    k = (len(values) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return values[int(k)]
    d0 = values[f] * (c - k)
    d1 = values[c] * (k - f)
    return d0 + d1


async def run_stage(
    client: httpx.AsyncClient,
    scenario: Scenario,
    concurrency: int,
    total_requests: int,
) -> dict:
    latencies_ms: list[float] = []
    status_counts: dict[int, int] = {}
    errors: list[str] = []
    ok_count = 0
    counter = 0
    lock = asyncio.Lock()

    async def worker() -> None:
        nonlocal counter, ok_count
        while True:
            async with lock:
                if counter >= total_requests:
                    return
                counter += 1
            started = time.perf_counter()
            try:
                response = await client.request(
                    scenario.method,
                    scenario.path,
                    headers=scenario.headers,
                    params=scenario.params,
                    json=scenario.json_body,
                    data=scenario.data,
                    files=scenario.files,
                )
                elapsed_ms = (time.perf_counter() - started) * 1000
                latencies_ms.append(elapsed_ms)
                status_counts[response.status_code] = status_counts.get(response.status_code, 0) + 1
                if response.status_code == 200:
                    ok_count += 1
            except Exception as exc:  # pragma: no cover - best-effort test tooling
                elapsed_ms = (time.perf_counter() - started) * 1000
                latencies_ms.append(elapsed_ms)
                errors.append(type(exc).__name__)

    started_at = time.perf_counter()
    await asyncio.gather(*(worker() for _ in range(concurrency)))
    duration_s = time.perf_counter() - started_at

    success_rate = ok_count / total_requests if total_requests else 0.0
    avg_ms = statistics.mean(latencies_ms) if latencies_ms else math.nan
    return {
        "scenario": scenario.name,
        "concurrency": concurrency,
        "total_requests": total_requests,
        "duration_s": round(duration_s, 3),
        "throughput_rps": round(total_requests / duration_s, 2) if duration_s else math.nan,
        "success_rate": round(success_rate, 4),
        "avg_ms": round(avg_ms, 2),
        "p50_ms": round(percentile(latencies_ms, 0.50), 2),
        "p95_ms": round(percentile(latencies_ms, 0.95), 2),
        "p99_ms": round(percentile(latencies_ms, 0.99), 2),
        "max_ms": round(max(latencies_ms), 2) if latencies_ms else math.nan,
        "status_counts": status_counts,
        "error_count": len(errors),
        "error_types": sorted(set(errors)),
    }


async def main() -> None:
    parser = argparse.ArgumentParser(description="Run async HTTP load tests.")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    parser.add_argument("--concurrency", default="10,20,50,100,150,200")
    parser.add_argument("--requests-per-stage", type=int, default=300)
    parser.add_argument("--include-diaries", action="store_true")
    parser.add_argument("--include-insight", action="store_true")
    parser.add_argument("--include-upload", action="store_true")
    parser.add_argument("--audio-file", default="")
    parser.add_argument("--insight-range", default="7d")
    args = parser.parse_args()

    concurrency_levels = [int(item) for item in args.concurrency.split(",") if item.strip()]
    audio_bytes: bytes | None = None
    audio_name = ""
    if args.include_upload:
        if not args.audio_file:
            raise SystemExit("--include-upload requires --audio-file")
        audio_path = Path(args.audio_file)
        if not audio_path.exists():
            raise SystemExit(f"Audio file not found: {audio_path}")
        audio_bytes = audio_path.read_bytes()
        audio_name = audio_path.name

    timeout = httpx.Timeout(20.0, connect=10.0)
    async with httpx.AsyncClient(base_url=args.base_url.rstrip("/"), timeout=timeout) as client:
        login_resp = await client.post(
            "/auth/login",
            json={"username": args.username, "password": args.password},
        )
        login_resp.raise_for_status()
        token = login_resp.json()["access_token"]

        scenarios = [
            Scenario(name="health", method="GET", path="/health"),
            Scenario(
                name="login",
                method="POST",
                path="/auth/login",
                json_body={"username": args.username, "password": args.password},
            ),
            Scenario(
                name="users_me",
                method="GET",
                path="/users/me",
                headers={"Authorization": f"Bearer {token}"},
            ),
        ]
        if args.include_diaries:
            scenarios.append(
                Scenario(
                    name="diaries_list",
                    method="GET",
                    path="/diaries",
                    headers={"Authorization": f"Bearer {token}"},
                )
            )
        if args.include_insight:
            scenarios.append(
                Scenario(
                    name="analytics_insight",
                    method="GET",
                    path="/analytics/insight",
                    headers={"Authorization": f"Bearer {token}"},
                    params={"range_key": args.insight_range},
                )
            )
        if args.include_upload and audio_bytes is not None:
            scenarios.append(
                Scenario(
                    name="diaries_upload_preserve",
                    method="POST",
                    path="/diaries/upload",
                    headers={"Authorization": f"Bearer {token}"},
                    data={
                        "preserve_fields": "true",
                        "transcript": "load test transcript",
                        "emotion_label": "Neutral",
                    },
                    files={
                        "audio": (
                            audio_name,
                            audio_bytes,
                            "audio/wav",
                        )
                    },
                )
            )

        results: list[dict] = []
        for scenario in scenarios:
            for concurrency in concurrency_levels:
                total_requests = max(args.requests_per_stage, concurrency * 4)
                result = await run_stage(client, scenario, concurrency, total_requests)
                results.append(result)
                print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    asyncio.run(main())
