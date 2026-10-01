"""Turns ghz JSON reports into the markdown table used in the README. Reports only measured values."""
import json
import pathlib
import sys

out_dir, target, duration, concurrency, connections = sys.argv[1:6]
rpcs = ["SubmitScore", "GetTopN", "GetPlayerRank", "GetPlayerStats"]


def ms(nanos):
    return f"{nanos / 1e6:.2f}"


def percentile(report, pct):
    for row in report.get("latencyDistribution") or []:
        if row["percentage"] == pct:
            return row["latency"]
    raise SystemExit(f"p{pct} missing from report")


print(f"Target `{target}`, {duration} per RPC, concurrency {concurrency}, {connections} connections\n")
print("| RPC | Requests | RPS | p50 (ms) | p99 (ms) | Non-OK |")
print("|---|---:|---:|---:|---:|---:|")
for rpc in rpcs:
    report = json.loads(pathlib.Path(out_dir, f"{rpc}.json").read_text())
    codes = report.get("statusCodeDistribution") or {}
    non_ok = sum(v for k, v in codes.items() if k != "OK")
    print(f"| {rpc} | {report['count']:,} | {report['rps']:,.0f} | {ms(percentile(report, 50))} "
          f"| {ms(percentile(report, 99))} | {non_ok} |")
