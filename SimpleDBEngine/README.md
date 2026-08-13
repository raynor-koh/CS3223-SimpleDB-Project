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

## Run the direct SimpleDB tests

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
