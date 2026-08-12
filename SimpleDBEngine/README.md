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
as the compilation output folder.

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
