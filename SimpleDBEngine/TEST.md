# Testing Guide

This document records the automated suites, manual examples, and expected
results for the integrated engine.

## Run the Full Automated Baseline

From the `SimpleDBEngine` directory, use PowerShell on Windows:

```powershell
powershell -NoProfile -File .\scripts\run-baseline.ps1
```

Or use Bash 4 or newer, including Linux/WSL:

```bash
bash scripts/run-baseline.sh
```

Both runners compile all Java source files into a fresh temporary directory,
then execute the same 28 suites with Java assertions enabled (`-ea`). They
isolate working directories and generated databases from the shared `studentdb`,
retain compiler output and per-suite logs, and print their artifact directory.
A compilation error or failed suite produces a nonzero exit status.

The latest recorded full run on **22 October 2026**, using OpenJDK/javac
**25.0.4.1**, compiled **208 Java files** and passed **28 suites / 13,690
reported checks**. The runner's final summary is:

```text
Baseline: 28 passed, 0 failed, 28 total.
```

This result was obtained with the Bash runner. The PowerShell runner has the
same suite list but was not executed in the audit environment. Check counts
are custom assertions, not line/branch coverage percentages or independent SQL
scenario counts. Benchmark/report timings must be collected separately.

To run individual suites with the commands below, first compile into `bin`
as described in [README.md](README.md#manual-compilation). The baseline runner
uses its own temporary classes directory and does not refresh `bin`.

Interactive lexer/parser examples and the live network/client checks
described below are additional checks outside the 28-suite baseline.
`ScanTest3` is now included in both runners.

## Index Backfill and Merge-Join Rewind Regressions

Both baseline runners include these two additional executable suites:

- `simpledb.index.IndexBackfillIntegrationTest`: creates hash and B-tree
  indexes after loading integer, varchar, and TIME keys, with duplicate keys
  and deleted slots. It compares complete SQL result bags and raw index RID
  probes against an independent row model, checks index-plan selection, and
  verifies later inserts/updates/deletes and database reopen. Direct metadata
  API creation and buffer restoration are covered too.
- `simpledb.materialize.MergeJoinRewindIntegrationTest`: repeatedly rewinds
  the same scan after partial and complete consumption, including duplicate
  keys, multiple natural sort runs, empty/no-match inputs, and a merge join
  repeatedly rewound as the RHS of nested loops. A direct sort-bookmark case
  verifies restoration of the selected run and end-of-run flags after
  exhaustion. Exact result bags retain duplicate multiplicities, and scans
  must release their buffers before commit.

Both suites failed against the original implementations. Index creation now
backfills live rows before publishing catalog metadata; merge rewind clears
the current join key, and sort bookmarks restore the complete merge state.
Each suite uses fresh temporary databases. Together they add 3,362 checks
to the previous 24-suite / 6,907-check baseline.

### Populated-Table Index Creation

After compiling the current source into `bin`, run:

```text
java -ea -cp bin simpledb.index.IndexBackfillIntegrationTest
```

Expected final output:

```text
All 5490 index-backfill integration checks passed.
```

The suite also inserts 512 unique B+-tree keys and 256 identical keys. It
checks buffer restoration after every insertion, directory/root growth,
overflow chains larger than the eight-buffer pool, exact RID retrieval and
absent keys before and after reopening. These 2,320 extra checks cover the
formatting-pin leak discovered by the experiment fixture; the new regression
failed on the first leaf split (24th unique insertion) before the fix.

The SQL fixture loads rows before creating the index, probes the same keys
before and after creation, then verifies persistence and later mutations. It
covers both hash and B-tree indexes with INTEGER, VARCHAR, and TIME keys.
Deleted slots must be skipped, duplicate-key rows must all be indexed, and
absent-key probes must remain empty. Result bags count each returned row so
extra index entries cannot be hidden by set comparison. A separate fixture
also creates populated indexes through `MetadataMgr.createIndex(...)`.

Before the fix, equality probes after index creation returned empty results.
The change applies to newly created indexes; it does not automatically repair
an incomplete index already present in an older database.

### Merge-Join Rewind and Sort Bookmarks

```text
java -ea -cp bin simpledb.materialize.MergeJoinRewindIntegrationTest
```

Expected final output:

```text
All 192 merge-rewind integration checks passed.
```

The suite checks repeated `beforeFirst()` calls on the **same scan**, rather
than only opening a new scan from the same plan. Fixtures cover all-equal keys,
multiple keys, duplicate matches across final sort runs, partial/full
consumption, and empty/no-match inputs. A nested-loop parent repeatedly rewinds
its merge-join RHS; the expected bag doubles every matching pair. A direct
sort-bookmark test exhausts both runs and restores the same bookmark repeatedly.

Before the fix, the all-equal fixture threw a NullPointerException after
rewind. Resetting only the merge key still failed a two-run duplicate fixture;
restoring the selected sort run and availability flags is also required.

## Lab 1: Support Non-Equality Predicates

### Lexer: Consume Comparison Operators

Run `src/simpledb/parse/LexerTest.java`.

For a valid comparison, the parsed operands and operator are printed in their
original order. For an invalid operator, the program prints
`Invalid comparison operator`.

| Input | Expected output |
| --- | --- |
| `age = 18` | `age = 18` |
| `age < 18` | `age < 18` |
| `age <= 18` | `age <= 18` |
| `age > 18` | `age > 18` |
| `age >= 18` | `age >= 18` |
| `age != 18` | `age != 18` |
| `age <> 18` | `age <> 18` |
| `age<=18` | `age <= 18` |
| `18 > age` | `18 > age` |
| `age ! 18` | `Invalid comparison operator` |

### Parser: Parse Non-Equality Terms

Run `src/simpledb/parse/ParserTest.java`.

The program prints `yes` when parsing succeeds and `no` when the statement has
invalid syntax. These tests validate integer constants, string constants,
field-to-field comparisons, and predicates connected by `AND`.

| Input | Expected output |
| --- | --- |
| `select age from student where age = 18` | `yes` |
| `select age from student where age < 18` | `yes` |
| `select age from student where age <= 18` | `yes` |
| `select age from student where age > 18` | `yes` |
| `select age from student where age >= 18` | `yes` |
| `select age from student where age != 18` | `yes` |
| `select age from student where age <> 18` | `yes` |
| `select name from student where name <> 'joe'` | `yes` |
| `select sid from student, enrol where sid < studentid` | `yes` |
| `select age, name from student where age >= 18 and name != 'joe'` | `yes` |
| `select age from student where age ! 18` | `no` |
| `select age from student where age == 18` | `no` |
| `select age from student where age >< 18` | `no` |

### Term: Deterministic Unit Tests

Run `src/simpledb/query/TermTest.java`.

This test uses in-memory `Scan` and `Plan` implementations. It does not create
or modify database files. It performs 85 checks in the following areas:

- all comparison operators for integers and strings;
- equality boundary cases for `<`, `<=`, `>`, and `>=`;
- left-to-right operand ordering;
- field-to-field, field-to-constant, and constant-to-field expressions;
- identical behavior for `!=` and `<>`;
- equality-only behavior in `equatesWithConstant()` and
  `equatesWithField()`;
- all six non-equality operators in field-to-constant,
  constant-to-field, and field-to-field optimizer checks;
- equality-only behavior through `Predicate`; and
- mixed predicates that contain both an inequality and a usable equality;
- equality, inequality, and constant-to-constant reduction factors;
- backward compatibility of the two-argument `Term` constructor; and
- rejection of unsupported operators during evaluation.

Representative comparison cases include:

| Comparison | Expected result |
| --- | --- |
| `10 < 20` | `true` |
| `20 < 10` | `false` |
| `10 <= 10` | `true` |
| `10 > 10` | `false` |
| `10 >= 10` | `true` |
| `10 != 20` | `true` |
| `10 <> 10` | `false` |
| `'alice' < 'bob'` | `true` |
| `'alice' >= 'bob'` | `false` |
| `'alice' <> 'alice'` | `false` |

Representative optimizer-safety cases include:

| Term | Check | Expected result |
| --- | --- | --- |
| `A = 10` | `equatesWithConstant("A")` | `10` |
| `A > 10` | `equatesWithConstant("A")` | `null` |
| `A = B` | `equatesWithField("A")` | `B` |
| `A != B` | `equatesWithField("A")` | `null` |
| `A = 10` with 50 distinct `A` values | reduction factor | `50` |
| `A < 10` | reduction factor | `2` |
| `10 < 20` | reduction factor | `1` |
| `20 < 10` | reduction factor | `Integer.MAX_VALUE` |

The expected final output is:

```text
All 85 Term checks passed.
```

### SelectScan: Non-Equality Integration Tests

Run `src/simpledb/query/ScanTest3.java`.

This test creates a local `scantest3` database and uses a real
`TableScan -> SelectScan -> Predicate -> Term` pipeline. It clears its test
table before inserting these deterministic records:

| A | B | C | D |
| ---: | --- | ---: | --- |
| 5 | `alice` | 10 | `bob` |
| 10 | `bob` | 10 | `bob` |
| 15 | `carol` | 10 | `bob` |

The 13 integration checks are:

| Predicate | Expected record count |
| --- | ---: |
| `A < C` | 1 |
| `A <= C` | 2 |
| `A > C` | 1 |
| `A >= C` | 2 |
| `A != C` | 2 |
| `A <> C` | 2 |
| `B < D` | 1 |
| `B >= D` | 2 |
| `B != D` | 2 |
| `B <> D` | 2 |
| `A > 10` | 1 |
| `B <> 'bob'` | 2 |
| `A >= 10 and B <> 'bob'` | 1 |

The expected final output is:

```text
All 13 ScanTest3 checks passed.
```

The generated `scantest3/` database directory is a test artifact and should
not be committed.

## Query Validation, DISTINCT, and EXPLAIN Tests

Both baseline runners include the following suites. Run an individual class
with `java -ea -cp bin <class>` after manual compilation.

| Class | Reported checks | Coverage |
| --- | ---: | --- |
| `simpledb.parse.QueryValidationParserTest` | 101 | Complete statements, malformed clauses, trailing tokens, exact integer limits, decimal rejection, quoted strings, and useful syntax diagnostics. |
| `simpledb.plan.QueryValidationIntegrationTest` | 964 | Missing/ambiguous tables and fields, incompatible types, invalid aggregates/grouping/ordering, recursive views and cycles, and valid queries across Basic/Better/Heuristic planners. |
| `simpledb.parse.DistinctParserTest` | 10 | DISTINCT parsing, case normalization, query rendering, and malformed syntax. |
| `simpledb.materialize.DistinctPlanTest` | 12 | Physical duplicate elimination. |
| `simpledb.materialize.DistinctQueryIntegrationTest` | 12 | DISTINCT through the query-planning/execution path. |
| `simpledb.parse.ExplainParserTest` | 8 | Wrapping SELECT with EXPLAIN and rejecting invalid commands. |
| `simpledb.plan.PlanExplainerTest` | 6 | Deterministic physical-plan formatting without opening execution scans. |
| `simpledb.plan.ExplainIntegrationTest` | 5 | Public EXPLAIN integration. |

Validation integration checks both SELECT planning and EXPLAIN rejection,
including unchanged file names and available buffers for invalid queries.
This is SELECT/EXPLAIN semantic validation; it is not comprehensive DDL/DML
error coverage. Valid views preserve their grouping, DISTINCT, ordering, and
eligible join policies.

DISTINCT after joined aggregation and projection is also covered by
`JoinFeatureIntegrationTest`. `QueryResourceIntegrationTest` guards real
EXPLAIN trees against execution, materialization, and buffer leaks. EXPLAIN
can still read metadata/statistics during planning; its estimates are not
measured execution timings. The direct SimpleIJ/API path supports EXPLAIN;
there is no JDBC EXPLAIN ResultSet command.

## SQL TIME Integration Tests

Run `scripts/run-baseline.sh` to compile the engine and include
`simpledb.test.TimeTypeIntegrationTest` in the regression suite. The test uses
an isolated temporary database and covers:

- `TIME` schema/catalog persistence and fixed-width record layout across reopen;
- midnight, fractional values, the last microsecond of the day, and rejection
  of malformed values, invalid components, excess precision, and `24:00:00`;
- insert, update, read, predicate comparisons, ordering, DISTINCT, and legacy
  integer/varchar behavior;
- duplicate-key lookup in both hash and B-tree indexes; and
- JDBC `Types.TIME` metadata, canonical string output, legacy `getTime()`, and
  exact `getObject(..., LocalTime.class)` retrieval.

Expected final output:

```text
All 31 TIME integration checks passed.
```

Its generated database is under the operating system's temporary directory.

`simpledb.test.TimeTypeRegressionTest` adds 70 checks for the TIME review fixes:

- reject mixed TIME/VARCHAR column comparisons before choosing any of the four
  join methods, while preserving valid TIME joins and quoted literal comparisons;
- reject invalid raw TIME writes before changing record bytes;
- preserve milliseconds in JDBC `getTime()` and all microseconds in `LocalTime`;
- reuse table schemas during inserts and bulk updates in both update planners;
- return `SQLException` for invalid labels, indexes, requested types, malformed
  network values, and transport failures.

Network conversion checks use a stub remote result set, without an RMI server.
Both Bash and PowerShell baseline runners include both TIME test classes.

## Typed Index Structures

### CREATE INDEX Parser Tests

Run:

```text
java -cp bin simpledb.parse.CreateIndexParserTest
```

The 24 checks cover:

- the default `hash` type when `USING` is omitted;
- explicit `USING hash` and `USING btree` clauses;
- normalization of index, table, field, and type names;
- rejection of unsupported and missing index types; and
- direct `CreateIndexData` validation for invalid and null types.

The expected final output is:

```text
All 24 CREATE INDEX parser checks passed.
```

### Typed Index Integration Tests

Run:

```text
java -cp bin simpledb.index.TypedIndexIntegrationTest
```

This test creates an isolated database under the operating system's temporary
directory rather than using `studentdb`. Its 56 checks cover:

- persistence of `hash` and `btree` values through `idxcat` and a database
  reopen;
- creation of the corresponding `HashIndex` and `BTreeIndex` objects;
- simultaneous hash and B-tree indexes in one database;
- index maintenance after insert, indexed-field update, and delete;
- direct retrieval through both index implementations;
- equality queries with the indexed field on either side; and
- correct results for `<`, `<=`, `>`, `>=`, `!=`, and `<>` predicates on an
  indexed field.

The expected final output is:

```text
All 56 typed-index integration checks passed.
```

### ORDER BY Parser Tests

Run:

```powershell
java -cp bin simpledb.parse.OrderByParserTest
```

The checks cover:

- queries with no `ORDER BY` clause;
- the default ascending direction;
- explicit `ASC` and `DESC` directions;
- multiple fields with mixed directions;
- case-insensitive sorting keywords;
- inclusion of sorting in `QueryData.toString()`; and
- malformed clauses with a missing `BY` or field.

The expected final output is:

```text
All 15 ORDER BY parser checks passed.
```

### ORDER BY Integration Tests

Run:

```powershell
java -cp bin simpledb.materialize.OrderByIntegrationTest
```

The checks cover:

- no `SortPlan` when `ORDER BY` is absent;
- a top-level `SortPlan` when ordering is requested;
- integer and string fields in ascending and descending order;
- multiple sort keys with mixed directions;
- an index-backed equality selection followed by sorting;
- sorting an empty query result safely; and
- both `HeuristicQueryPlanner` and `BasicQueryPlanner`.

The integration test creates its database under the operating system's
temporary directory and does not modify `studentdb`.

The expected final output is:

```text
All 14 ORDER BY integration checks passed.
```

### Aggregate Parser Tests

Run:

```bash
java -cp bin simpledb.parse.AggregateParserTest
```

The checks cover field and aggregate SELECT items, `count(*)`, multiple
`GROUP BY` fields, `WHERE + GROUP BY + ORDER BY` clause order, stable aggregate
output names, malformed function calls, invalid `sum(*)`, and rejection of
the misspelling `agv`.

Expected output:

```text
All 30 aggregate parser checks passed.
```

### Aggregate Integration Tests

Run:

```bash
java -cp bin simpledb.materialize.AggregateIntegrationTest
```

This isolated temporary-database suite covers `SUM`, `COUNT`, decimal
`AVG`, string and integer `MIN`/`MAX`, grouped/global aggregation,
`GROUP BY` without aggregates, `WHERE` before aggregation, ordering by an
aggregate result, planner validation, empty input, overflow, and scan rewind.

Expected output:

```text
All 94 aggregate integration checks passed.
```

### Partitioned Hash Join Integration Tests

Run:

```bash
java -cp bin simpledb.multibuffer.HashJoinIntegrationTest
```

The suite exercises `HashJoinPlan` directly with integer and string keys,
negative integer hash codes, duplicate-key many-to-many matches, no matches,
scan rewind, buffer release, and invalid join-key validation. The broader join
suite also verifies that `TablePlanner` selects `HashJoinPlan` for unindexed
equality joins.

Expected output:

```text
All 23 hash join integration checks passed.
```

### Join Integration Tests

Run:

```powershell
java -cp bin simpledb.opt.JoinIntegrationTest
```

On macOS or Linux, the command is the same:

```bash
java -cp bin simpledb.opt.JoinIntegrationTest
```

This test creates a uniquely named database under the operating system's
temporary directory. It does not create or modify `studentdb`. The 74 checks
cover:

- nested-loops joins for equality, `<`, `<=`, `>`, `>=`, `!=`, and `<>`;
- duplicate join keys, no matches, empty left and right inputs, and repeated
  scans after `beforeFirst()`;
- fields read from both inputs and conjunctions containing multiple join terms;
- sort-merge joins over integer and string keys;
- duplicate keys on both sides and many-to-many merge-join output;
- index joins using a hash index, including duplicate entries, reversed
  equality operands, empty outer input, and no matches;
- index maintenance after updates and inserts through `IndexUpdatePlanner`;
- direct physical-plan selection through `TablePlanner`;
- cross-product fallback for unrelated tables;
- local selections and residual join predicates;
- three-table left-deep planning; and
- consecutive queries through one `HeuristicQueryPlanner` instance.

The expected final output is:

```text
All 74 join integration checks passed.
```

The test also verifies that `NestedLoopJoinPlan` opens a
`NestedLoopJoinScan`, and that `MergeJoinPlan` opens a `MergeJoinScan`.

### Join Policy Integration Tests

Both baseline runners include `simpledb.opt.JoinPolicyIntegrationTest`.
It uses fresh temporary databases and performs 236 checks covering:

- strict index, hash, merge, and nested-loop plans through the public planner;
- explicit expected result bags, including duplicate projected tuples;
- integer/string keys, reversed equality operands, local selections, and
  residual equality/non-equality predicates;
- clear planning failures for unavailable methods without a product bypass;
- three-table continuation under strict index/hash/merge policies: skip an
  initially unusable relation, join a later candidate, then revisit the first;
- failures after a partial join, with blocked relations, predicates, allowed
  methods, and current fields in the diagnostic; successful planner reuse after
  failure;
- disconnected products alone and alongside a compliant three-table chain,
  without allowing unrelated tables to bypass a blocked connected join;
- disconnected products, hash buffer eligibility, and allowed merge fallback;
- index selection remaining available when index joins are disabled; and
- index-selection output appearing only with opt-in tracing.

The physical tree is inspected as well as the result bag. EXPLAIN estimates
for nested-loop equality joins also exercise the regression that previously
caused recursive distinct-value estimation to overflow the stack.

Expected final output:

```text
All 236 join-policy integration checks passed.
```

To check tracing explicitly after compilation, run in a fresh JVM:

```text
java -ea -Dsimpledb.traceJoins=true -cp bin simpledb.opt.JoinPolicyIntegrationTest
```

### Lab 7 Combined Join Integration Tests

Both baseline runners include `simpledb.test.JoinFeatureIntegrationTest`, which
performs 947 checks using the fixed `JoinFeatureFixture` and typed tuple-bag helpers
in `QueryResultAssertions`. Each run creates an isolated temporary database.
The four tables have 3, 5, 6, and 7 records; indexes are created before inserts
and the database is reopened to refresh statistics consistently.

Expected answers are computed by plain Java loops over source fixture rows,
without engine scans, join operators, or SQL predicate evaluation. The checks
cover duplicate keys (2 x 3 matches), unmatched keys on both sides, additional
equality conditions, non-equality residuals, local filters, and empty results.
Four-table chains preserve all three join conditions and return 14 rows before
extra filtering. Projections include integer and string fields and deliberately
duplicate output tuples; comparison retains each tuple's occurrence count.

Each equality workload runs under strict index/hash/merge/nested-loop policies
and the default policy. The actual physical tree must contain the expected
one or three join nodes and no products. A pure non-equality join also runs
under nested loops. The indexed-selection-to-hash case guards against output
partitions exhausting buffers already occupied by the input scan.

Final-stage combinations add 13 cases per profile for all four strict policies
and the default policy. Explicit expected tuples cover joined global/grouped
COUNT, SUM, AVG, MIN, and MAX, including four-table aggregation. Equal results
from different groups remain duplicate rows until DISTINCT follows projection;
dropping all or part of the grouping key must collapse only identical complete
projected tuples. Paired queries with and without DISTINCT verify both counts.

Queries with ORDER BY compare exact typed sequences in addition to result bags.
Cases exercise ASC/DESC and DESC/ASC combinations, aggregate-output sort fields,
and three-field ordering, with ties on primary keys. Physical trees must retain
aggregation before projection, DISTINCT after projection, and outer ordering
after DISTINCT. The sequence helper closes every scan just like bag comparison.

Expected final output:

```text
All 947 join-feature integration checks passed.
```

### Query Resource Integration Tests

Both baseline runners include `simpledb.test.QueryResourceIntegrationTest`.
Run it separately after compilation with:

```text
java -ea -cp bin simpledb.test.QueryResourceIntegrationTest
```

The suite has a 120-second deadline covering setup and checks. Work runs in a
daemon thread, so a stuck engine call cannot keep the test JVM alive after
timeout; the failure includes the worker stack. Databases are isolated under
the system temporary directory and their path is printed.

- All four strict join profiles exercise early close before reading, early
  close after one row, complete consumption, and repeated opening of the same
  plan. Cases include a four-table chain, joined aggregation, DISTINCT/order,
  empty inputs on either side, both inputs empty, and no matches.
- Each scan's close must restore buffer availability in the same transaction,
  before commit could hide leaked pins. Complete tuple bags remain correct on
  every repeated execution.
- Two 24-row wide-layout inputs each physically exceed the eight-buffer pool.
  Descending input requires multiple sorting runs/merge passes; exact ascending
  output is checked. Single-key skew returns all 24 x 24 = 576 expected pairs.
  Runtime fallback is inspected directly, including its early-close cleanup.
- Public `Planner.explain` traverses guarded real combined-query trees; opening
  the root or any displayed child fails the test. Repeated display must leave
  buffer availability and temporary-table files unchanged. Executing the same
  unguarded physical plan confirms that the file check detects materialization.

These cases also guard the fixes for an unclosed B-tree initialization leaf
page and for a final skewed hash partition being skipped before its fallback
scan could be consumed. They do not claim EXPLAIN avoids metadata/statistics
reads or that all possible resource/error paths are covered.

Expected final output:

```text
All 347 query-resource integration checks passed.
```

### Indexed Updates and Joined Aggregates

Both baseline runners include `simpledb.test.IndexedUpdateAggregateIntegrationTest`.
It performs 3,590 checks across isolated hash-backed and B-tree-backed fixtures.
Indexes on the fact join key, fact amount, and dimension join key are created
before insertion, and all mutations use `IndexUpdatePlanner` through `Planner`.

Twelve states per index type cover initial data, inserts, moving an indexed join
key, changing an indexed aggregate input, a bulk key update, deleting matching
rows from multiple indexes, reinsertion into deleted slots, dimension-key
updates, deleting unmatched rows, emptying facts, negative reinsertion, and
deleting matching dimensions. Each state is checked before commit, then again
with a new SimpleDB instance and freshly reconstructed metadata.

Checks compare full table scans with an independent source-row model, raw index
RID probes with complete expected tuples, and SQL equality probes with verified
`IndexSelect` nodes. Probe values include old, new, deleted, and absent keys.
Joined global/grouped COUNT, SUM, AVG, MIN, and MAX are computed independently
from model pairs and compared under default and strict index-join policies;
actual `IndexJoin` nodes are required. Duplicate dimension keys multiply fact
rows and aggregate contributions. Scans/indexes close explicitly, and available
buffers must be restored before commit.

The test expects DECIMAL(16,6) AVG results. Empty global queries mixing COUNT
with other aggregate functions retain the documented no-row policy. Expected final output:

```text
All 3590 indexed-update aggregate integration checks passed.
```

### Grouping Validation and Aggregate Boundaries

Both baseline runners include `simpledb.test.AggregateSemanticsIntegrationTest`,
which performs 209 checks through heuristic and basic planners. The parser suite
also rejects ungrouped selected fields when GROUP BY has no aggregate functions.
Valid grouping-only queries, projections of a subset of grouping fields, and
ordinary SELECT queries continue to work.

Invalid selections fail in `createQueryPlan` and EXPLAIN with a grouping
diagnostic, without creating execution temporary tables or leaking buffers.
A sentinel input plan whose `open()` and execution-estimate methods throw
confirms that shared aggregate validation rejects invalid selections using only
the input schema, before execution.

AVG tests cover negative and positive fractions, six-place HALF_UP rounding,
and same-sign pairs at the 32-bit limits. AVG results have DECIMAL type.
Empty physical tables and filters matching no rows return one zero-count row
for COUNT-only queries, including multiple counts and DISTINCT/ORDER BY.
Empty grouped queries and global queries containing other aggregate functions
retain the documented no-row policy pending SQL NULL support.

Expected final output:

```text
All 209 aggregate-semantics integration checks passed.
```

### Decimal AVG Results

Both baseline runners include `simpledb.test.DecimalAverageIntegrationTest`
(1,032 checks). It covers DECIMAL(16,6) constants and eight-byte storage,
positive/negative fractions, repeating decimals, exact HALF_UP ties, 32-bit
input boundaries, multiple external sort runs, DISTINCT, materialization,
same-scan rewind, views, and decimal MIN/MAX. Basic, Better, and Heuristic
planners are exercised; scans must restore their buffers.

Embedded JDBC and network-adapter tests cover BigDecimal/object/string/double
retrieval, explicit integer conversion, precision/scale metadata, invalid
arguments, and simulated transport/parsing failures. Network-adapter tests use
a stub remote result set; the baseline does not require a listening RMI socket.

```bash
java -ea -cp bin simpledb.test.DecimalAverageIntegrationTest
```

```text
All 1032 decimal-average integration checks passed.
```

### Join Strategy Tracing

Join tracing is disabled by default. Enable it with the JVM property
`simpledb.traceJoins`:

```powershell
java -Dsimpledb.traceJoins=true -cp bin simpledb.test.SimpleIJ
```

The trace identifies the physical strategy selected by `TablePlanner`:

```text
[planner] index join: did = majorid
[planner] partitioned hash join: did = deptid
[planner] nested-loops join: majorid<did
[planner] cross product
```

Trace messages can describe candidate plans considered during optimization.
Use EXPLAIN to inspect the selected physical tree, including the join order,
access paths, and residual predicates.

### Recreate the Student Database

The engine-side `simpledb.test.CreateStudentDB` creates the indexes needed for
the index-join demonstration. Run it from the `SimpleDBEngine` directory:

```powershell
if (Test-Path .\studentdb) {
    Remove-Item -Recurse -Force .\studentdb
}
java -cp bin simpledb.test.CreateStudentDB
```

On macOS or Linux:

```bash
rm -rf -- ./studentdb
java -cp bin simpledb.test.CreateStudentDB
```

This setup creates:

- `idx_stud_major` on `STUDENT(MajorId)` using a hash index; and
- `idx_enroll_sid` on `ENROLL(StudentId)` using a B-tree index.

Do not run the setup program repeatedly against the same database. Recreate
the database first when starting over.

### SimpleDBClients Network Testing

Compile `SimpleDBEngine` first, then compile `SimpleDBClients`. Start the
server from the `SimpleDBEngine` directory using the prepared database:

```powershell
java -Dsimpledb.traceJoins=true -cp bin simpledb.server.StartServer studentdb
```

From `SimpleDBClients`, run `SimpleIJ` and connect with:

```text
jdbc:simpledb://localhost
```

Do not run `network.CreateStudentDB`; the engine-side setup has already
created and populated the database. For network mode, planner traces appear
in the server terminal.

Representative join queries are:

```sql
select SName, DName from DEPT, STUDENT where DId = MajorId
```

This should use index join because `STUDENT.MajorId` is indexed.

```sql
select DName, Title from DEPT, COURSE where DId = DeptId
```

This should use a partitioned hash join because `COURSE.DeptId` is not indexed
and the default configuration has enough buffers. Index joins remain preferred
when an applicable index exists; merge join remains the equality fallback when
there are fewer than three available buffers.

```sql
select SName, DName from STUDENT, DEPT where MajorId < DId
```

This should use nested-loops join because the predicate is not equality.

```sql
select SName, Title from STUDENT, COURSE
```

This should use the cross-product path because no predicate connects the
tables.

### Manual SimpleIJ Tests

After creating `studentdb`, run:

```powershell
java -cp bin simpledb.test.SimpleIJ
```

Representative queries are:

```sql
select sid, sname, gradyear from student order by gradyear
```

```sql
select sid, sname, gradyear from student order by gradyear asc, sname desc
```

```sql
select sid, sname, gradyear from student where gradyear >= 2021 order by gradyear desc, sname
```

The first query uses ascending order by default. The other queries verify
explicit and mixed directions together with a non-equality predicate.
