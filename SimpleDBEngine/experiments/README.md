# Join experiments

This folder contains the experiment code and evidence used to fill in
[`report.typ`](../report.typ). It uses synthetic tables, independently of the
student/demo database. The engine source is compiled as it stands; no join
implementation or planner is replaced by the experiment code.

Initial validation exposed a B+-tree split-page pin leak in `BTPage.appendNew`.
That engine resource bug is fixed, with regression checks for leaf/directory
splits, root growth, duplicate overflow chains, buffer restoration and reopen.
All accepted experiment results use that fix; pre-fix attempts failed before
measurements were accepted.

## Run

Use Python 3.10+ and JDK 17+ (tested with the project's OpenJDK 25). Run these
commands from the `SimpleDBEngine` directory. On Windows, `py -3` can replace
`python3`; Java and javac must be on PATH. Paths containing spaces are supported.

First check the harness:

```bash
python3 -m unittest discover -s experiments -p 'test_*.py'
python3 experiments/run.py --smoke --output experiments/results/smoke
```

The smoke run uses 32 and 128 fact rows and one measured repetition. It checks
the pipeline and all eight query/profile combinations; it is not a final
performance study.

A first calibration run with larger datasets:

```bash
python3 experiments/run.py --sizes 128,256 --warmups 1 --runs 3 --output experiments/results/calibration
```

For the main experiment, start with three sizes and repeated measurements:

```bash
python3 experiments/run.py --sizes 128,256,512 --warmups 3 --runs 10 --output experiments/results/main
```

Increase sizes after inspecting pilot runtimes. Larger ladders such as
`--sizes 512,1024,2048` are configurable, but nested-loop work can grow roughly
quadratically and synchronous temporary-file I/O is expensive in this engine.
In the initial pilot, the 1,024-row two-table sort-merge query took roughly
112 seconds per execution. Start with one measured repetition at candidate
sizes before choosing a large matrix. `--payload 32` increases each table's row width; keep it the same
for an entire comparison. `--seed 3223`, `--block-size 400`, `--buffers 8`, and
`--heap-mb 512` are configurable and recorded. Defaults are 16 payload bytes,
400-byte blocks, eight buffers, and a 512 MiB maximum JVM heap. The block-size
minimum is 400 and buffer-count minimum is eight for this four-table workload.

Every output directory must be new or empty. The runner refuses to overwrite
existing evidence. Omit `--output` to create a directory named with a UTC
timestamp. `--timeout 1800` bounds each fixture/profile process; increase it
for slow, large experiments. A failed or timed-out profile makes the overall
run fail, retains diagnostic logs/raw partial records, and produces no accepted
summary. Check `run.json`: only `status: complete` is ready for analysis.

## Dataset

For fact size **N**, let **K = max(4, floor(N/8))**,
**D = max(2, floor(K/4))**, and **T = max(2, floor(D/4))**.

| Table | Rows | Integer fields | Other field |
| --- | ---: | --- | --- |
| `benchfact` | N | `fid`, `fkey` | `fpad varchar(payload)` |
| `benchmid` | 2K | `mid`, `mkey`, `mnext` | `mpad varchar(payload)` |
| `benchdim` | D | `did`, `dkey`, `dnext` | `dpad varchar(payload)` |
| `benchtail` | T | `tid`, `tkey` | `tpad varchar(payload)` |

Every middle key occurs exactly twice. Fact keys are generated using a seeded
Java PRNG, with one deliberately unmatched key every twentieth row (including
row zero). Middle-to-dimension and dimension-to-tail references always match.
Each table is shuffled before insertion, so sort-merge is not handed sorted
input. Payloads contain fixed-width `x` strings; their purpose is to control
record width, not model realistic text content.

Consequently, both queries return exactly **2 × (N − ceil(N/20))** rows. The
projection intentionally preserves duplicate tuples; neither query uses
DISTINCT. Both ends of all three join edges have B+-tree indexes. The exact
same populated, committed, indexed snapshot is used by every profile. The
non-index profiles retain those indexes but their plans must use their
requested join method. Fixture creation and index building are not timed.

For N = 4096, the four table sizes are 4096, 1024, 128, and 32. The eight-buffer
pool is much smaller than the fact table. The demo's 100-row cap does not apply
to this separate experimental dataset.

The fixture loader creates tables through SQL, bulk-inserts deterministic
rows through TableScan, and then creates indexes through SQL with backfilling.
CSV exports preserve the actual insertion order; `schema.sql`, `tables.csv`,
`key-histograms.csv`, and `expected.csv` describe the fixture, exact join-key
frequencies, and expected output counts.

## Queries and profiles

Experiment 1:

```sql
select fkey, mkey from benchfact, benchmid where fkey = mkey
```

Experiment 2:

```sql
select fkey, mnext, dnext, tkey from benchfact, benchmid, benchdim, benchtail
where fkey = mkey and mnext = dkey and dnext = tkey
```

Each query runs with four strict `JoinPolicy.only(method)` profiles:

| Profile | Suggested report label for Experiment 2 |
| --- | --- |
| `NESTED_LOOP` | P1 |
| `MERGE` | P2 |
| `HASH` | P3 |
| `INDEX` | P4 (additional comparison) |

Each saved two-table plan must contain exactly one requested join operator;
each four-table plan must contain exactly three. Other join methods and
Cartesian products are rejected. Negative estimates are rejected; this does
not prove that every possible integer-estimate overflow has been ruled out.
Exact plan text must remain stable across repetitions. Summary generation also
requires four distinct plan IDs for each dataset/query.

The heuristic planner chooses the relation order. The harness records that
order and does not claim to force it or implement another optimizer. For
Experiment 2, P1–P3 compare algorithms on the same logical query and fixture;
include the actual saved trees when explaining any differences in relation
order or estimates.

## Timing and correctness

For each dataset/query/profile, a separate JVM runs the configured warmups
followed by measured repetitions. Before **every** repetition, it copies the
same committed snapshot to a fresh temporary directory, opens a new engine,
and plans the query. This keeps previous temporary tables and modified buffer state out of the
next engine. Trial files can accumulate within a profile, but their lifetime
is bounded by that worker process. Each profile's temporary
databases are removed after its JVM exits, so the engine's open file handles
have been released. Compile output and fixture databases also live in system
temporary directories, outside the submission source and demo database.

The timed interval is:

1. Start `System.nanoTime()` before `plan.open()`.
2. Open the plan, consume every row, and read every projected integer into a
   preallocated result buffer.
3. Close the scan, then stop the timer.

Thus sorting, partitioning, row decoding, and result-buffer writes are
included. Fixture copying/loading, engine initialization/recovery/statistics,
planning, EXPLAIN formatting, allocation of the result buffer, result-bag
comparison, commit, and printing are excluded. This measures execution plus
minimal row retrieval, not planning/end-to-end latency or measured disk I/O.

After timing, all returned tuples are compared with an independent Java
reference join over the generated rows, with exact duplicate multiplicities.
Warmup rows are checked too. Too many/few rows, wrong tuple values, missing
duplicates, or unreleased buffers fail the run. Timing a query that returns
the wrong answer never counts as a successful result.

Every trial has a fresh SimpleDB buffer pool, but reopening/statistics and
snapshot copying affect the OS cache. **These are not guaranteed cold-disk
measurements.** Warmups also warm the JVM within each profile. Profile order
rotates across dataset/query combinations; repetitions within a profile remain
contiguous rather than interleaved. The manifest records this policy so the
report can describe the actual method.

HashJoin may repartition or fall back to nested loops internally. The existing
engine exposes no fallback counter. HASH rows explicitly say
`hash_fallback=not_instrumented`; describe them as the partitioned-hash policy,
not certified pure in-memory hash execution. Estimated costs are saved in plan
text and must not be substituted for elapsed time.

## Output and report figures

The included [pilot summary](results/pilot/summary.csv) contains two fact sizes
(256 and 1,024), four profiles for each experiment, one warmup and three
measured runs per profile: 64 verified trials, of which 48 are measured.
The commands below plot this completed pilot. Substitute a later complete
run directory when choosing the final report data.

Each completed run contains:

- `run.json`: parameters, environment, source revision, policy, completion status.
- `source-sha256.csv`: hashes of the frozen engine/benchmark Java sources compiled
  for this run, and the Python runner.
- `source-snapshot.zip`: those exact sources, retained so later edits cannot
  erase the code needed to reproduce the measurements.
- `dataset-sha256.csv`: hashes of the exported fixture definitions and data.
- `datasets/n<size>/`: table CSVs, schema/index definitions, counts/record sizes,
  query texts, and independently expected result counts.
- `plans/`: exact physical trees, named by size, experiment, and method.
- `raw/`: per-profile timing CSVs, including warmups.
- `results.csv`: all verified raw repetitions.
- `summary.csv`: measured-only median, p25/p75, min/max, mean, and sample standard
  deviation in milliseconds, plus row counts, plan IDs and relation order.
- `logs/`: compiler, fixture, and per-profile logs.

Keep raw records and manifests alongside the report; they are reproducibility
evidence. Choose a single complete main run for comparisons rather than mixing
different runs or selecting the fastest repetitions. With one measured run,
spread estimates are uninformative; smoke/pilot results should be labelled as
such.

The manifest's Git revision identifies the checkout's parent commit; local
changes may be present. The source hashes identify the exact compiled bytes
and take precedence over assuming that the parent commit alone reproduces a run.

Plotting is optional for the benchmark and requires Matplotlib. A local virtual
environment keeps the plotting dependency with this experiment setup:

```bash
python3 -m venv experiments/.venv
experiments/.venv/bin/python -m pip install -r experiments/requirements-plot.txt
experiments/.venv/bin/python experiments/plot.py experiments/results/pilot/summary.csv --log-y
```

On Windows:

```powershell
py -3 -m venv experiments/.venv
.\experiments\.venv\Scripts\python.exe -m pip install -r experiments/requirements-plot.txt
.\experiments\.venv\Scripts\python.exe experiments/plot.py experiments/results/pilot/summary.csv --log-y
```

This generates `two-table-runtime.svg` and `four-table-runtime.svg` alongside
the summary. Small solid circles show median execution times, connected by
lines. Quartiles remain available in `summary.csv`. The fact-row axis is
linear, with an exact, comma-separated row-count
label at every measured dataset size. Add `--log-x` for sizes spanning orders
of magnitude, or `--log-y` for runtimes spanning orders of magnitude; each
logarithmic axis is explicitly labeled.

In `report.typ`, replace the chart placeholder with, for example:

```typst
image("experiments/results/pilot/two-table-runtime.svg", width: 100%)
```

Use `csv("experiments/results/pilot/results.csv")` for raw records and
`raw(read("experiments/results/pilot/plans/n1024-experiment-2-hash.txt"), block: true)`
for a saved tree. The report template's appendix references do not establish
that extra pages are permitted; keep the submitted report within the audited
four-page limit unless the course instructions explicitly allow otherwise.
