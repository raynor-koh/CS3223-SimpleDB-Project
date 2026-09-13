# Testing Guide

This document records the test cases added for each lab feature.

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
temporary directory. It does not create or modify `studentdb`. The 55 checks
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
All 55 join integration checks passed.
```

The test also verifies that `NestedLoopJoinPlan` opens a
`NestedLoopJoinScan`, and that `MergeJoinPlan` opens a `MergeJoinScan`.

### Join Strategy Tracing

Join tracing is disabled by default. Enable it with the JVM property
`simpledb.traceJoins`:

```powershell
java -Dsimpledb.traceJoins=true -cp bin simpledb.test.SimpleIJ
```

The trace identifies the physical strategy selected by `TablePlanner`:

```text
[planner] index join: did = majorid
[planner] sort-merge join: did = deptid
[planner] nested-loops join: majorid<did
[planner] cross product
```

For two-table queries, the printed strategy is the selected strategy. During a
multi-table heuristic-planning pass, the planner may print strategies while it
evaluates candidate tables.

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

This should use sort-merge join because `COURSE.DeptId` is not indexed.

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
