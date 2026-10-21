# SimpleDBEngine

SimpleDBEngine is a Java implementation of the SimpleDB database engine.

This guide is for setting it up on Visual Studio Code. The [project write-up](https://www.comp.nus.edu.sg/~tankl/cs3223/project.html) on the CS3223 course website uses the Eclipse IDE.

## Requirements

- A JDK installed on your computer (Preferably Java 25)
- Visual Studio Code
- The **Extension Pack for Java** extension for VS Code

Check that Java and the Java compiler are available from a terminal:

```text
java -version
javac -version
```

## Project structure

```text
SimpleDBEngine/
├── src/       Java source files
├── bin/       Compiled `.class` files (created during compilation)
├── lib/       Optional dependency libraries
└── .vscode/   VS Code project settings and tasks
```

The Java source code is under `src/simpledb` and is organized into packages such
as `buffer`, `file`, `query`, `record`, and `tx`.

## Open the project

Open the `SimpleDBEngine` folder itself in VS Code:

```text
CS3223-SimpleDB-Project/SimpleDBEngine
```

Do not open only the parent folder or create another nested `SimpleDBEngine`
folder.

The file `.vscode/settings.json` configures `src` as the source folder and `bin`
as the compilation output folder. The source code includes the engine packages
under `src/simpledb` and the direct-use student database tests under
`src/simpledb/test`.

## Compile the project

The project includes a VS Code task in `.vscode/tasks.json` named
**Compile SimpleDBEngine**. The task compiles all Java files under `src` and
places the resulting `.class` files in `bin`.

To run it:

1. Open the `SimpleDBEngine` folder in VS Code.
2. Press `Ctrl+Shift+B`.
3. Select **Compile SimpleDBEngine**.

You can also run it through **Terminal → Run Build Task**.

### Manual compilation

From the `SimpleDBEngine` folder, run this in a PowerShell terminal:

```powershell
New-Item -ItemType Directory -Force bin | Out-Null
$files = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
javac -d bin $files
```

This creates the `bin` folder if necessary and compiles all Java source files
into it.

In detail:

- `New-Item -ItemType Directory -Force bin` creates the `bin` folder if needed.
- `| Out-Null` hides the folder-creation output.
- `Get-ChildItem -Recurse -Filter *.java src` finds all Java files under `src`.
- `ForEach-Object { $_.FullName }` collects each file's full path in `$files`.
- `javac -d bin $files` compiles the files and writes the `.class` files to
  `bin`.

On macOS or Linux, run the following commands from the `SimpleDBEngine` folder:

```bash
mkdir -p bin
find src -name '*.java' -print0 | xargs -0 javac -d bin
```

`mkdir -p bin` creates the output folder if necessary. The `find` command
locates all Java source files under `src`, and `javac -d bin` compiles them into
the `bin` folder.

Compilation warnings, such as warnings about deprecated Java APIs, do not stop
the build. Red compiler errors must be fixed before the affected classes can be
used.

## Run the SimpleDB server

The project includes a launch configuration named **SimpleDB Server** in
`.vscode/launch.json`. It runs `simpledb.server.StartServer` using the
`studentdb` database by default.

1. Open the **Run and Debug** view in VS Code.
2. Select **SimpleDB Server**.
3. Press `F5`.

The server prints `database server ready` when it is running and listens on RMI
port `1099`. Stop it with **Shift+F5**. The server database is created in the
current working directory, normally:

```text
SimpleDBEngine/studentdb/
```

To use a different database name, set the launch configuration's `args` field,
for example:

```json
"args": ["mydatabase"]
```

## Run the automated regression suite

The baseline runners compile the source into a fresh temporary directory and
run all 26 automated suites without using the shared `studentdb`. From the
project directory, use either:

```powershell
powershell -NoProfile -File .\scripts\run-baseline.ps1
```

```bash
bash scripts/run-baseline.sh
```

The Bash runner requires Bash 4 or newer. Both runners print the artifact
directory containing compiled classes, compiler output, per-suite logs, and
isolated databases. They return a nonzero exit status if compilation or a
suite fails; the temporary artifacts remain available for inspection.

The latest recorded Bash run on 22 October 2026 compiled 207 Java files and
passed **26 suites / 10,269 checks**, including the populated-table index
creation and merge-join rewind regressions. The PowerShell suite list matches;
that runner was not executed in the audit environment.

See [TEST.md](TEST.md) for individual commands, coverage, and expected results.
These regression results do not provide the experimental timings required for
the report; remaining demo/report work is tracked in
[PROJECT_READINESS_AUDIT.md](PROJECT_READINESS_AUDIT.md).

## Run the direct SimpleDB examples

The programs under `src/simpledb/test` access the engine classes directly and
do not require the server. Run them from the `SimpleDBEngine` project using the
**Run** link above each class's `main` method, or by selecting the class in the
**Run and Debug** view.

Run `CreateStudentDB` once to create the database, then run:

```text
simpledb.test.CreateStudentDB
simpledb.test.StudentMajor
simpledb.test.ChangeMajor
simpledb.test.FindMajors
```

`FindMajors` prompts for a department name such as `compsci`, `math`, or
`drama`. Do not run `CreateStudentDB` repeatedly against the same database. If
the database becomes corrupted or needs to be recreated, stop any running
program, delete `SimpleDBEngine/studentdb/`, and run `CreateStudentDB` again.

### Run the direct SimpleIJ

`simpledb.test.SimpleIJ` is an interactive SQL client that accesses the
SimpleDB classes directly. Unlike the client-project version, it does not ask
for a JDBC connection string and does not require the server.

Run it from the `SimpleDBEngine` project using the **Run** link above its
`main` method. It uses the local `studentdb` database and displays an `SQL>`
prompt. For example:

```text
SQL> select SName, DName from DEPT, STUDENT where MajorId = DId
SQL> exit
```

Run `simpledb.test.CreateStudentDB` first if `studentdb` has not been created.
The direct `SimpleIJ` database is located at:

```text
SimpleDBEngine/studentdb/
```

The direct `SimpleIJ` and the network server use separate database instances
when they run from different project directories, even if both databases are
named `studentdb`.

Enter one SQL statement per line and omit trailing semicolons. To keep the
demo separate from your existing database, launch the setup program and direct
client from the same fresh working directory, using an absolute classpath to
the compiled engine.

## Hash and B+-tree indexes

`CREATE INDEX` accepts `USING hash` or `USING btree`; omitting the clause
defaults to hash. Index creation now indexes existing live rows, including
duplicate keys, before publishing the index in the catalog. This works through
both SQL and `MetadataMgr.createIndex(...)`.

For example, run these commands once in a fresh test database:

```sql
create table indexdemo(id int, keyval int)
insert into indexdemo(id, keyval) values (1, 7)
insert into indexdemo(id, keyval) values (2, 7)
create index idx_demo on indexdemo(keyval) using btree
explain select id from indexdemo where keyval = 7
select id from indexdemo where keyval = 7 order by id
```

The SELECT returns IDs `1` and `2`; EXPLAIN shows an `IndexSelect` node.
Use `USING hash` instead to try the other structure in a fresh fixture.
Both types support INTEGER, VARCHAR, and TIME keys. Inserts, indexed-field
updates, and deletes maintain the indexes through `IndexUpdatePlanner`.
The planner uses these indexes for equality probes; non-equality conditions
use other access paths, including for B+-tree indexes.

This change does not automatically repair incomplete indexes created by an
older build. Regenerate affected demo/test databases from their fixtures.

## SQL TIME values

Declare time-of-day fields with `TIME` and supply quoted `HH:MM:SS` values,
optionally with one to six fractional digits:

```sql
create table demotime(tid int, starttime time)
insert into demotime(tid, starttime) values (1, '09:30:00.123456')
select tid, starttime from demotime where starttime > '09:00:00' order by starttime
```

TIME has microsecond precision and no date or time zone. Its valid range is
midnight through `23:59:59.999999`; invalid values such as `24:00:00` are
rejected. Storage, comparisons, joins, ordering, DISTINCT, and both index
types preserve the typed value.

JDBC metadata reports `Types.TIME`. Use `getString()` for canonical text or
`getObject(..., LocalTime.class)` for exact microseconds. Legacy `getTime()`
retains milliseconds, while `java.sql.Time.toString()` displays whole seconds.

## Query validation

SELECT and EXPLAIN validate table/view existence, missing or ambiguous fields,
comparison types, aggregate arguments, GROUP BY membership, and ORDER BY
availability before query execution. Views are validated recursively, including
cycle detection. Basic, Better, and Heuristic planners share this validation.

For example, `select missing from student` reports
`SELECT field not found: missing`. Names are unqualified: qualified names such
as `student.sid` and table aliases are not supported. ORDER BY fields must
appear in the SELECT output. Semantic validation of updates and schema changes
is not comprehensive.

## Lab 6 SQL extensions

### `DISTINCT`

Use `DISTINCT` immediately after `SELECT` to remove duplicate output rows.
Duplicates are compared across every field in the select list.

```sql
select distinct majorid from student
select distinct majorid, gradyear from student order by majorid, gradyear
```

`DISTINCT` is evaluated after projection and after any aggregation or grouping.
It does not promise an output order by itself; add `ORDER BY` whenever the
display order matters.

### `EXPLAIN`

Prefix a supported `SELECT` query with `EXPLAIN` in the direct `SimpleIJ`
client to display its physical query plan:

```sql
explain select distinct majorid from student order by majorid desc
```

The output is a tree of physical operators with estimated block accesses and
record counts. It is read-only: `EXPLAIN` builds and formats the plan but does
not open a scan or execute the query. It supports the same query features as
the engine's `SELECT` parser, including `DISTINCT`, `WHERE`, `GROUP BY`,
aggregates, joins, and `ORDER BY`.

`EXPLAIN` is currently available through `Planner.explain(...)` and the direct
`SimpleIJ` client. It is not exposed as a JDBC `ResultSet` command.

## Grouping and aggregate semantics

Whenever a query contains GROUP BY or aggregate functions, every selected
non-aggregate field must appear in GROUP BY. Validation happens during planning,
including EXPLAIN, before query execution. For example:

```sql
select majorid from student group by majorid
```

is valid, while `select sname from student group by majorid` is rejected with
`SELECT field sname must appear in GROUP BY`. Selecting a subset of multiple
grouping fields is allowed. Ordinary SELECT without grouping or aggregates
retains its existing behavior.

AVG accepts integer inputs and returns an integer, truncating toward zero:
the average of -5 and 2 is -1; the average of 5 and -2 is 1. Accumulation uses
a wider integer so intermediate sums can exceed the 32-bit input range.

The current empty-input policy returns **zero rows for global aggregation**,
including COUNT(*) and COUNT(field). This differs from standard SQL's single
zero-count row. SUM, AVG, MIN, MAX, and mixed aggregate select lists also
return no row when there is no input; no NULL values are introduced. Empty
grouped queries likewise produce no groups. This policy applies both to empty
tables and filters that match no rows.

## Join-method configuration

Java callers can configure eligible joins per heuristic planner:

```java
QueryPlanner qp = new HeuristicQueryPlanner(mdm,
        JoinPolicy.only(JoinMethod.HASH));
```

`JoinMethod` and `JoinPolicy` are in `simpledb.opt`. The existing constructor
allows all methods in the normal priority: index, hash, merge, then nested
loops. Mixed policies use `new JoinPolicy(EnumSet.of(...))`. Policies restrict
join operators while retaining their eligibility checks and all query filters;
they do not disable index selection or change the heuristic relation order.
An unavailable connected join fails during planning instead of silently using
another algorithm or a product. Disconnected products remain supported.
The planner searches all remaining relations for an eligible next join before
failing. Diagnostics identify blocked relations and connecting predicates,
allowed methods, and the partial plan's fields. It does not backtrack earlier
relation-order choices, so failure describes the current heuristic continuation
rather than proving every possible join order infeasible.
There is no SQL or command-line policy selector. A planned hash join may still
use its existing internal nested-loop fallback for oversized/skewed partitions.

Merge-join scans support repeated `beforeFirst()` calls after partial or full
consumption, retaining all duplicate matches. Rewind resets the current join
key; sort bookmarks restore the selected run and its availability flags as
well as record positions. The regression suite also exercises a nested-loop
parent repeatedly rewinding a merge-join input.

Index selection and join tracing are silent by default. Enable them with:

```text
java -Dsimpledb.traceJoins=true -cp bin simpledb.test.SimpleIJ
```

Trace messages can describe candidate plans considered during optimization;
use EXPLAIN to inspect the selected physical tree.
