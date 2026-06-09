#!/usr/bin/env python3
"""Scan BFS synthetic wikimix-target shapes on hardware.

The scan intentionally starts with the most wikimix-like prefix graph. If that
candidate does not fail during the gate trials, the script exits early instead
of spending time on smaller graphs that are less likely to reproduce the issue.
"""

import argparse
import datetime as _dt
import shlex
import subprocess
import sys
import time
from dataclasses import dataclass


class Tee:
    def __init__(self, *streams):
        self.streams = streams

    def write(self, data: str) -> int:
        for stream in self.streams:
            stream.write(data)
        return len(data)

    def flush(self) -> None:
        for stream in self.streams:
            stream.flush()


@dataclass(frozen=True)
class Candidate:
    kind: str
    first: int
    frontier: int
    degree: int
    visited: int = 0

    @property
    def next_count(self) -> int:
        return self.frontier * self.degree

    @property
    def graph_arg(self) -> str:
        if self.kind == "prefix":
            return f"synthetic:wm_target_prefix:{self.first}:{self.frontier}:{self.next_count}"
        if self.kind == "burst":
            return f"synthetic:wm_target_burst:{self.frontier}:{self.degree}:{self.visited}"
        raise ValueError(f"unknown candidate kind {self.kind}")

    @property
    def vertices(self) -> int:
        if self.kind == "prefix":
            return 1 + self.first + self.frontier + self.next_count
        return 1 + self.frontier + self.next_count

    @property
    def edges(self) -> int:
        if self.kind == "prefix":
            return self.first + self.frontier + self.next_count
        return self.frontier + self.frontier * (self.degree + self.visited)

    def label(self) -> str:
        if self.kind == "prefix":
            return (
                f"prefix A={self.first} N={self.frontier} D={self.degree} "
                f"M={self.next_count}"
            )
        return (
            f"burst N={self.frontier} D={self.degree} V={self.visited}"
        )


def now() -> str:
    return _dt.datetime.now().strftime("%H:%M:%S")


def ints(csv: str) -> list[int]:
    out: list[int] = []
    for part in csv.split(","):
        part = part.strip()
        if part:
            out.append(int(part))
    return out


def classify(returncode: int, output: str) -> str:
    if "[BFS] WATCHDOG:" in output:
        return "stall"
    if "[BFS] MISMATCH" in output or "[BFS] FAIL" in output:
        return "mismatch"
    if "[BFS] PASS" in output and returncode == 0:
        return "pass"
    if "failed to open cu context" in output or "No such device" in output:
        return "xrt_error"
    if returncode == 0:
        return "pass_no_marker"
    return "error"


def interesting_lines(output: str) -> list[str]:
    keep = []
    needles = (
        "[BFS] Graph:",
        "[BFS] FPGA level profile:",
        "[BFS] GBBS level profile:",
        "[BFS] WATCHDOG:",
        "[BFS] MISMATCH",
        "[BFS] FAIL",
        "[BFS] PASS",
        "failed to open cu context",
        "No such device",
    )
    for line in output.splitlines():
        if any(needle in line for needle in needles):
            keep.append(line)
    return keep


def reset_device(args: argparse.Namespace, reason: str) -> None:
    if not args.reset_device:
        return
    cmd = [
        "xrt-smi",
        "--batch",
        "--force",
        "reset",
        "-d",
        args.reset_device,
        "-t",
        args.reset_type,
    ]
    quoted = " ".join(shlex.quote(x) for x in cmd)
    if args.setup:
        actual_cmd = [
            "/bin/bash",
            "-lc",
            f"source {shlex.quote(args.setup)} >/dev/null; exec {quoted}",
        ]
    else:
        actual_cmd = cmd

    print(
        f"[{now()}] RESET {args.reset_device} type={args.reset_type} reason={reason}",
        flush=True,
    )
    start = time.monotonic()
    proc = subprocess.run(
        actual_cmd,
        cwd=args.cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=args.reset_timeout,
    )
    elapsed = time.monotonic() - start
    for line in proc.stdout.splitlines():
        print(f"          {line}", flush=True)
    if proc.returncode != 0:
        raise RuntimeError(
            f"xrt-smi reset failed with rc={proc.returncode} after {elapsed:.2f}s"
        )
    print(
        f"[{now()}] RESET complete rc={proc.returncode} elapsed={elapsed:.2f}s",
        flush=True,
    )
    if args.post_reset_sleep > 0:
        print(
            f"[{now()}] WAIT  post-reset settle {args.post_reset_sleep:.1f}s",
            flush=True,
        )
        time.sleep(args.post_reset_sleep)


def run_once(args: argparse.Namespace, cand: Candidate, run_index: int,
             total_runs: int) -> tuple[str, float]:
    cmd = [
        args.binary,
        args.xclbin,
        cand.graph_arg,
        str(args.source),
        str(args.max_depth),
        str(args.watchdog),
    ]
    if args.setup:
        quoted = " ".join(shlex.quote(x) for x in cmd)
        shell_cmd = f"source {shlex.quote(args.setup)} >/dev/null; exec {quoted}"
        actual_cmd = ["/bin/bash", "-lc", shell_cmd]
    else:
        actual_cmd = cmd

    print(
        f"[{now()}] START {cand.label()} run {run_index}/{total_runs} "
        f"vertices={cand.vertices} edges={cand.edges}",
        flush=True,
    )
    start = time.monotonic()
    proc = subprocess.run(
        actual_cmd,
        cwd=args.cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        timeout=args.command_timeout,
    )
    elapsed = time.monotonic() - start
    status = classify(proc.returncode, proc.stdout)
    print(
        f"[{now()}] END   {cand.label()} run {run_index}/{total_runs} "
        f"status={status} rc={proc.returncode} elapsed={elapsed:.2f}s",
        flush=True,
    )
    for line in interesting_lines(proc.stdout):
        print(f"          {line}", flush=True)
    return status, elapsed


def is_candidate_failure(status: str) -> bool:
    return status in ("stall", "mismatch", "host_timeout")


def run_candidate(args: argparse.Namespace, cand: Candidate,
                  trials: int) -> dict[str, int]:
    counts: dict[str, int] = {}
    total_elapsed = 0.0
    for i in range(1, trials + 1):
        xrt_retries = 0
        while True:
            try:
                status, elapsed = run_once(args, cand, i, trials)
            except subprocess.TimeoutExpired:
                status, elapsed = "host_timeout", args.command_timeout
                print(
                    f"[{now()}] END   {cand.label()} run {i}/{trials} "
                    f"status={status} elapsed>{elapsed:.2f}s",
                    flush=True,
                )
            if status != "xrt_error":
                break
            xrt_retries += 1
            print(
                f"[{now()}] INFRA {cand.label()} run {i}/{trials}: "
                f"xrt_error retry {xrt_retries}/{args.xrt_retries}",
                flush=True,
            )
            reset_device(args, "after-xrt_error")
            if xrt_retries >= args.xrt_retries:
                raise RuntimeError(
                    f"too many XRT errors for {cand.label()} run {i}/{trials}"
                )
        counts[status] = counts.get(status, 0) + 1
        total_elapsed += elapsed
        fail_count = sum(v for k, v in counts.items() if is_candidate_failure(k))
        print(
            f"[{now()}] SUMMARY {cand.label()} after {i}/{trials}: "
            f"failures={fail_count} ({100.0 * fail_count / i:.1f}%) "
            f"counts={counts} avg_elapsed={total_elapsed / i:.2f}s",
            flush=True,
        )
        if is_candidate_failure(status):
            reset_device(args, f"after-{status}")
        if args.stop_after_failures > 0 and fail_count >= args.stop_after_failures:
            print(
                f"[{now()}] MOVE ON {cand.label()}: reached "
                f"{fail_count} failure(s) after {i}/{trials} trial(s)",
                flush=True,
            )
            break
    return counts


def make_candidates(args: argparse.Namespace) -> list[Candidate]:
    firsts = ints(args.first_levels)
    frontiers = ints(args.frontiers)
    degrees = ints(args.degrees)
    visiteds = ints(args.visited_edges)
    candidates: list[Candidate] = []
    if "prefix" in args.kinds:
        candidates.extend(
            Candidate("prefix", first, frontier, degree)
            for first in firsts
            for frontier in frontiers
            for degree in degrees
        )
    if "burst" in args.kinds:
        candidates.extend(
            Candidate("burst", 1, frontier, degree, visited)
            for frontier in frontiers
            for degree in degrees
            for visited in visiteds
        )
    candidates.sort(key=lambda c: (c.vertices, c.edges), reverse=True)
    if args.max_vertices > 0:
        candidates = [c for c in candidates if c.vertices <= args.max_vertices]
    if args.max_edges > 0:
        candidates = [c for c in candidates if c.edges <= args.max_edges]
    return candidates


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cwd", default="/beta/bradley/HardCilk/xclbin-workspace/BFS")
    parser.add_argument(
        "--binary",
        default="./src/host/build/projects/BFS/BFS_xrt",
    )
    parser.add_argument(
        "--xclbin",
        default="build_dir.hw.xilinx_u55c_gen3x16_xdma_3_202210_1/BFS.xclbin",
    )
    parser.add_argument("--setup", default="/opt/xilinx/xrt/setup.sh")
    parser.add_argument("--source", type=int, default=0)
    parser.add_argument("--max-depth", type=int, default=0)
    parser.add_argument("--watchdog", type=float, default=15.0)
    parser.add_argument("--command-timeout", type=float, default=90.0)
    parser.add_argument("--reset-device", default="0000:01:00.1")
    parser.add_argument("--reset-type", default="user")
    parser.add_argument("--reset-timeout", type=float, default=30.0)
    parser.add_argument("--post-reset-sleep", type=float, default=2.0)
    parser.add_argument("--xrt-retries", type=int, default=3)
    parser.add_argument("--no-reset", action="store_true")
    parser.add_argument("--gate-trials", type=int, default=12)
    parser.add_argument("--trials", type=int, default=12)
    parser.add_argument(
        "--stop-after-failures",
        type=int,
        default=0,
        help="stop the current candidate after this many failures; 0 disables",
    )
    parser.add_argument("--kinds", default="prefix")
    parser.add_argument("--first-levels", default="20")
    parser.add_argument("--frontiers", default="7529,4096,2048,1024,512,256,128,64")
    parser.add_argument("--degrees", default="112,96,64,48,32,24,16,8,4,2,1")
    parser.add_argument("--visited-edges", default="0,1")
    parser.add_argument("--max-vertices", type=int, default=0)
    parser.add_argument("--max-edges", type=int, default=0)
    parser.add_argument("--log-file", default="")
    parser.add_argument(
        "--no-gate",
        action="store_true",
        help="scan all candidates even if the largest one does not fail",
    )
    args = parser.parse_args()
    args.kinds = tuple(k.strip() for k in args.kinds.split(",") if k.strip())
    if args.no_reset:
        args.reset_device = ""
    return args


def main() -> int:
    args = parse_args()
    log_handle = None
    if args.log_file:
        log_handle = open(args.log_file, "a", buffering=1)
        sys.stdout = Tee(sys.stdout, log_handle)
        sys.stderr = Tee(sys.stderr, log_handle)
        print(f"[{now()}] LOG {args.log_file}", flush=True)

    candidates = make_candidates(args)
    if not candidates:
        print("No candidates selected.", file=sys.stderr)
        return 2

    print(f"[{now()}] BFS wm_target scan starting in {args.cwd}", flush=True)
    print(f"[{now()}] binary={args.binary}", flush=True)
    print(f"[{now()}] xclbin={args.xclbin}", flush=True)
    print(f"[{now()}] candidates={len(candidates)} kinds={','.join(args.kinds)}", flush=True)
    reset_device(args, "before-first-run")

    results: list[tuple[Candidate, dict[str, int]]] = []
    gate = candidates[0]
    if not args.no_gate:
        print(
            f"[{now()}] GATE largest candidate first: {gate.label()} "
            f"for {args.gate_trials} trials",
            flush=True,
        )
        gate_counts = run_candidate(args, gate, args.gate_trials)
        results.append((gate, gate_counts))
        gate_failures = sum(
            v for k, v in gate_counts.items() if is_candidate_failure(k)
        )
        if gate_failures == 0:
            print(
                f"[{now()}] EARLY STOP: largest candidate had 0 failures in "
                f"{args.gate_trials} trials; not scanning smaller points.",
                flush=True,
            )
            return 2
        candidates = candidates[1:]

    for idx, cand in enumerate(candidates, start=1):
        print(
            f"[{now()}] CANDIDATE {idx}/{len(candidates)} {cand.label()}",
            flush=True,
        )
        counts = run_candidate(args, cand, args.trials)
        results.append((cand, counts))

    print(f"[{now()}] FINAL RESULTS", flush=True)
    for cand, counts in results:
        total = sum(counts.values())
        failures = sum(
            v for k, v in counts.items() if is_candidate_failure(k)
        )
        print(
            f"{cand.label()} vertices={cand.vertices} edges={cand.edges} "
            f"failures={failures}/{total} ({100.0 * failures / total:.1f}%) "
            f"counts={counts}",
            flush=True,
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
