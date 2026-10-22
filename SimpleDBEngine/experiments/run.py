#!/usr/bin/env python3
"""Compile and run isolated, result-verified SimpleDB join experiments."""

import argparse
import csv
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import zipfile


ROOT = Path(__file__).resolve().parent.parent
METHODS = ("NESTED_LOOP", "MERGE", "HASH", "INDEX")
MAIN = "simpledb.experiments.JoinBenchmark"


def positive(text):
    value = int(text)
    if value < 1:
        raise argparse.ArgumentTypeError("must be positive")
    return value


def sizes_argument(text):
    try:
        values = [int(value) for value in text.split(",")]
    except ValueError as error:
        raise argparse.ArgumentTypeError("use comma-separated row counts") from error
    if not values or len(set(values)) != len(values) or any(n < 16 or n > 100000 for n in values):
        raise argparse.ArgumentTypeError("use distinct sizes between 16 and 100000")
    return sorted(values)


def execute(command, log, timeout, cwd=ROOT):
    with log.open("w", encoding="utf-8") as output:
        try:
            result = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT,
                                    timeout=timeout, check=False)
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(f"Timed out after {timeout}s; see {log}") from error
    if result.returncode:
        tail = "\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-25:])
        raise RuntimeError(f"Command failed; see {log}\n{tail}")


def percentile(values, quantile):
    ordered = sorted(values)
    position = (len(ordered) - 1) * quantile
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def validate_and_summarize(records, sizes, warmups, runs):
    groups = {}
    expected = {(experiment, f"n{size}", method)
                for size in sizes for experiment in (1, 2) for method in METHODS}
    for row in records:
        key = (int(row["experiment"]), row["dataset_id"], row["profile"])
        if key not in expected:
            raise ValueError(f"Unexpected workload: {key}")
        if row["result_verified"] != "true" or int(row["elapsed_ns"]) <= 0:
            raise ValueError("Unverified or invalid timing")
        if row["warmup"] not in ("true", "false"):
            raise ValueError("Invalid warmup flag")
        if int(row["fact_rows"]) != int(row["dataset_id"][1:]) or int(row["rows"]) <= 0:
            raise ValueError("Invalid input/output size")
        groups.setdefault(key, []).append(row)
    if set(groups) != expected:
        raise ValueError(f"Missing profiles: {sorted(expected - set(groups))}")
    summaries = []
    for key, group in sorted(groups.items()):
        for flag, count in (("true", warmups), ("false", runs)):
            numbers = sorted(int(row["run"]) for row in group if row["warmup"] == flag)
            if numbers != list(range(1, count + 1)):
                raise ValueError(f"Missing/duplicate repetitions for {key}, warmup={flag}")
        for field in ("plan_id", "join_order", "rows", "block_size", "buffers", "jdk", "cache_policy"):
            if len({row[field] for row in group}) != 1:
                raise ValueError(f"Inconsistent {field} across repetitions: {key}")
        measured = [int(row["elapsed_ns"]) / 1_000_000 for row in group if row["warmup"] == "false"]
        experiment, dataset, method = key
        first = group[0]
        summaries.append({
            "experiment": experiment, "dataset_id": dataset, "fact_rows": first["fact_rows"],
            "profile": method, "samples": runs, "rows": first["rows"],
            "median_ms": statistics.median(measured), "p25_ms": percentile(measured, 0.25),
            "p75_ms": percentile(measured, 0.75), "min_ms": min(measured), "max_ms": max(measured),
            "mean_ms": statistics.mean(measured),
            "stdev_ms": statistics.stdev(measured) if len(measured) > 1 else 0.0,
            "plan_id": first["plan_id"], "join_order": first["join_order"],
            "plan_file": f"plans/{dataset}-experiment-{experiment}-{method.lower()}.txt",
        })
    for size in sizes:
        for experiment in (1, 2):
            subset = [row for row in summaries if row["dataset_id"] == f"n{size}"
                      and row["experiment"] == experiment]
            if len({row["plan_id"] for row in subset}) != len(METHODS):
                raise ValueError("Physical profiles did not produce four distinct plans")
            if len({row["rows"] for row in subset}) != 1:
                raise ValueError("Profiles disagree on output row counts")
    return summaries


def write_csv(path, rows):
    if not rows:
        raise ValueError("Refusing to write an empty result set")
    with path.open("w", encoding="utf-8", newline="") as output:
        writer = csv.DictWriter(output, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)


def cpu_name():
    cpu = platform.processor()
    if not cpu and Path("/proc/cpuinfo").is_file():
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    return cpu or "not detected"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", type=sizes_argument, default=[128, 256, 512],
                        help="fact-table row counts (default: 128,256,512)")
    parser.add_argument("--seed", type=int, default=3223)
    parser.add_argument("--payload", type=int, default=16, help="VARCHAR bytes per table row, 0..64")
    parser.add_argument("--warmups", type=int, default=3)
    parser.add_argument("--runs", type=positive, default=10, help="measured repetitions per profile")
    parser.add_argument("--block-size", type=positive, default=400)
    parser.add_argument("--buffers", type=positive, default=8)
    parser.add_argument("--heap-mb", type=positive, default=512)
    parser.add_argument("--timeout", type=positive, default=1800, help="seconds per fixture/profile process")
    parser.add_argument("--java", default="java")
    parser.add_argument("--javac", default="javac")
    parser.add_argument("--output", type=Path, help="new/empty output directory")
    parser.add_argument("--smoke", action="store_true", help="sizes 32,128; zero warmups; one measured run")
    args = parser.parse_args()
    if args.smoke:
        args.sizes, args.warmups, args.runs = [32, 128], 0, 1
    if not (0 <= args.payload <= 64 and 0 <= args.warmups and args.warmups + args.runs <= 100
            and args.block_size >= 400 and args.buffers >= 8 and args.heap_mb >= 64
            and -(2**63) <= args.seed < 2**63):
        parser.error("invalid payload, repetitions, block/buffer/heap size, or seed")
    for tool in (args.java, args.javac):
        if shutil.which(tool) is None:
            parser.error(f"Required executable not found: {tool}")
    output = args.output or Path(__file__).resolve().parent / "results" / datetime.now(timezone.utc).strftime("run-%Y%m%dT%H%M%SZ")
    output = output.resolve()
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        parser.error(f"Output must be new or empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    (output / "logs").mkdir()
    manifest = {
        "status": "running", "started_utc": datetime.now(timezone.utc).isoformat(),
        "sizes": args.sizes, "seed": args.seed, "payload_bytes": args.payload,
        "warmups": args.warmups, "measured_runs": args.runs,
        "block_size": args.block_size, "buffers": args.buffers, "max_heap_mb": args.heap_mb,
        "os": platform.platform(), "cpu": cpu_name(), "logical_cpus": os.cpu_count(),
        "python": platform.python_version(), "smoke_only": args.smoke,
        "timed_region": "plan.open; read all projected integer fields into a preallocated buffer; scan.close",
        "excluded": ["fixture setup/copy", "engine reopen/recovery/statistics", "planning/EXPLAIN",
                     "result-buffer allocation", "full tuple-bag validation", "commit", "printing"],
        "cache_policy": "fresh engine/buffer pool per trial; OS cache uncontrolled and affected by snapshot copies",
        "jvm_policy": "one JVM per dataset/query/profile; warmups and measured runs share that JVM",
        "profile_order": "rotated across datasets/experiments; repetitions within each profile are contiguous",
        "hash_fallback": "not instrumented; HASH may internally repartition or use nested loops",
        "source_policy": "Java sources are snapshotted before compilation; hashes describe compiled bytes",
        "command": [sys.executable, *sys.argv],
        "jobs": [],
    }
    started = time.monotonic()
    print(f"Results: {output}", flush=True)
    try:
        version = subprocess.run([args.java, "-version"], capture_output=True, text=True, check=True)
        manifest["java"] = version.stderr.strip() or version.stdout.strip()
        try:
            revision = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT,
                                      capture_output=True, text=True, check=True)
            manifest["git_revision"] = revision.stdout.strip()
        except (OSError, subprocess.CalledProcessError):
            manifest["git_revision"] = "unavailable"
        (output / "run.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        sources = sorted((ROOT / "src").rglob("*.java")) + sorted((ROOT / "experiments" / "src").rglob("*.java"))
        with tempfile.TemporaryDirectory(prefix="simpledb-experiment-build-") as build:
            build = Path(build)
            classes = build / "classes"
            classes.mkdir()
            frozen_sources = []
            provenance = []
            for source in sources:
                relative = source.relative_to(ROOT)
                contents = source.read_bytes()
                frozen = build / "source" / relative
                frozen.parent.mkdir(parents=True, exist_ok=True)
                frozen.write_bytes(contents)
                frozen_sources.append(frozen)
                provenance.append({"path": relative.as_posix(), "sha256": hashlib.sha256(contents).hexdigest()})
            runner_contents = Path(__file__).read_bytes()
            provenance.append({"path": Path(__file__).resolve().relative_to(ROOT).as_posix(),
                               "sha256": hashlib.sha256(runner_contents).hexdigest()})
            write_csv(output / "source-sha256.csv", provenance)
            with zipfile.ZipFile(output / "source-snapshot.zip", "w", zipfile.ZIP_DEFLATED) as archive:
                for source, frozen in zip(sources, frozen_sources):
                    archive.write(frozen, source.relative_to(ROOT).as_posix())
                archive.writestr("experiments/run.py", runner_contents)
            source_list = build / "sources.txt"
            source_list.write_text("\n".join('"' + path.as_posix() + '"' for path in frozen_sources) + "\n", encoding="utf-8")
            execute([args.javac, "-d", str(classes), "@" + str(source_list)], output / "logs" / "compile.log", args.timeout)
            java = [args.java, "-ea", "-Xms64m", f"-Xmx{args.heap_mb}m", "-cp", str(classes), MAIN]
            for dataset_index, size in enumerate(args.sizes):
                dataset = f"n{size}"
                parameters = [str(size), str(args.seed), str(args.payload), str(args.block_size), str(args.buffers)]
                with tempfile.TemporaryDirectory(prefix=f"simpledb-fixture-{dataset}-") as fixture:
                    snapshot = Path(fixture) / "database"
                    print(f"Preparing {dataset} ...", flush=True)
                    execute(java + ["prepare", str(snapshot), str(output / "datasets" / dataset), *parameters],
                            output / "logs" / f"{dataset}-prepare.log", args.timeout)
                    for experiment in (1, 2):
                        shift = (dataset_index + experiment - 1) % len(METHODS)
                        order = METHODS[shift:] + METHODS[:shift]
                        for method in order:
                            print(f"{dataset}, experiment {experiment}, {method}: "
                                  f"{args.warmups} warmups + {args.runs} measured", flush=True)
                            job = {"dataset_id": dataset, "experiment": experiment, "profile": method,
                                   "started_utc": datetime.now(timezone.utc).isoformat()}
                            manifest["jobs"].append(job)
                            with tempfile.TemporaryDirectory(prefix="simpledb-experiment-trials-") as trials:
                                execute(java + ["run", str(snapshot), str(output), *parameters, str(experiment),
                                                method, str(args.warmups), str(args.runs), trials],
                                        output / "logs" / f"{dataset}-experiment-{experiment}-{method.lower()}.log",
                                        args.timeout)
                            job["finished_utc"] = datetime.now(timezone.utc).isoformat()
        records = []
        for raw in sorted((output / "raw").glob("*.csv")):
            with raw.open(encoding="utf-8", newline="") as stream:
                records.extend(csv.DictReader(stream))
        summaries = validate_and_summarize(records, args.sizes, args.warmups, args.runs)
        for size in args.sizes:
            with (output / "datasets" / f"n{size}" / "expected.csv").open(encoding="utf-8", newline="") as stream:
                expected = {int(row["experiment"]): int(row["output_rows"]) for row in csv.DictReader(stream)}
            for row in summaries:
                if row["dataset_id"] == f"n{size}" and int(row["rows"]) != expected[row["experiment"]]:
                    raise ValueError("Measured rows disagree with fixture manifest")
        write_csv(output / "results.csv", records)
        write_csv(output / "summary.csv", summaries)
        fixture_files = sorted((output / "datasets").rglob("*"))
        write_csv(output / "dataset-sha256.csv", [
            {"path": path.relative_to(output).as_posix(), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
            for path in fixture_files if path.is_file()])
        manifest["status"] = "complete"
        manifest["verified_trials"] = len(records)
        print(f"Completed: {len(records)} verified trials; results.csv and summary.csv saved.", flush=True)
    except (OSError, RuntimeError, ValueError, subprocess.CalledProcessError) as error:
        manifest["status"] = "failed"
        manifest["error"] = str(error)
        print(f"Experiment failed: {error}", file=sys.stderr, flush=True)
        return 1
    except KeyboardInterrupt:
        manifest["status"] = "interrupted"
        print("Experiment interrupted; partial logs retained.", file=sys.stderr, flush=True)
        return 130
    finally:
        manifest["finished_utc"] = datetime.now(timezone.utc).isoformat()
        manifest["wall_seconds"] = time.monotonic() - started
        (output / "run.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
