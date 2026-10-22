#!/usr/bin/env python3
"""Create report-ready SVG runtime charts from a completed summary.csv."""

import argparse
import csv
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("summary", type=Path)
    parser.add_argument("--log-x", action="store_true", help="logarithmic row-count axis")
    parser.add_argument("--log-y", action="store_true", help="logarithmic runtime axis")
    args = parser.parse_args()
    manifest = json.loads((args.summary.parent / "run.json").read_text(encoding="utf-8"))
    if manifest["status"] != "complete":
        parser.error("run.json must describe a completed, verified run")
    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        from matplotlib.ticker import NullLocator, StrMethodFormatter
    except ImportError:
        parser.error("Install plotting dependencies with: python -m pip install -r experiments/requirements-plot.txt")
    with args.summary.open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source))
    methods = ("NESTED_LOOP", "MERGE", "HASH", "INDEX")
    labels = ("Nested loops", "Sort-merge", "Partitioned hash", "Index join")
    for experiment, filename in ((1, "two-table-runtime.svg"), (2, "four-table-runtime.svg")):
        figure, axis = plt.subplots(figsize=(6.4, 3.6), constrained_layout=True)
        for method, label in zip(methods, labels):
            points = sorted((row for row in rows if int(row["experiment"]) == experiment
                             and row["profile"] == method), key=lambda row: int(row["fact_rows"]))
            medians = [float(row["median_ms"]) for row in points]
            axis.plot([int(row["fact_rows"]) for row in points], medians,
                      marker="o", markersize=4,
                      markeredgewidth=1, linewidth=1.4, label=label)
        sizes = sorted({int(row["fact_rows"]) for row in rows
                        if int(row["experiment"]) == experiment})
        if args.log_x:
            axis.set_xscale("log")
        axis.set_xticks(sizes)
        axis.xaxis.set_major_formatter(StrMethodFormatter("{x:,.0f}"))
        axis.xaxis.set_minor_locator(NullLocator())
        axis.tick_params(axis="x", labelsize=9, pad=6)
        axis.set_xlabel("Fact table size (rows" + (", log scale)" if args.log_x else ")"))
        axis.set_ylabel("Execution time (ms" + (", log scale)" if args.log_y else ")"))
        if args.log_y:
            axis.set_yscale("log")
        else:
            axis.set_ylim(bottom=0)
        axis.set_title(f"Experiment {experiment}: median execution time")
        axis.grid(True, alpha=0.25)
        axis.legend(fontsize=8)
        target = args.summary.parent / filename
        figure.savefig(target, format="svg", metadata={"Date": None})
        plt.close(figure)
        print(target)


if __name__ == "__main__":
    main()
