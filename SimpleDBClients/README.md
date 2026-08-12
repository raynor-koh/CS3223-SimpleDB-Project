# SimpleDBClients

This project contains Java client programs that use the `SimpleDBEngine`.

This guide is for setting it up on Visual Studio Code. The [project write-up](https://www.comp.nus.edu.sg/~tankl/cs3223/project.html) on the CS3223 course website uses the Eclipse IDE.

## Requirements

- A JDK installed on your computer
- Visual Studio Code
- The **Extension Pack for Java** extension for VS Code
- A compiled copy of `SimpleDBEngine`

Check that Java and the Java compiler are available from a terminal:

```text
java -version
javac -version
```

## Project structure

```text
SimpleDBClients/
├── src/       Client source files
├── bin/       Compiled `.class` files (created during compilation)
├── lib/       Optional dependency libraries
└── .vscode/   VS Code project settings and tasks
```

The client source code is under `src/simpleclient`.

## Open the project

Open the `SimpleDBClients` folder itself in VS Code:

```text
CS3223-SimpleDB-Project/SimpleDBClients
```

Do not create another nested `SimpleDBClients` folder.

The file `.vscode/settings.json` configures `src` as the source folder, `bin`
as the output folder, and `../SimpleDBEngine/bin` as a referenced library. This
allows the client code to resolve classes from the SimpleDBEngine.

## Compile the projects

Compile `SimpleDBEngine` before compiling the clients. From the
`SimpleDBEngine` folder, press `Ctrl+Shift+B` and run **Compile SimpleDBEngine**.

Then, from the `SimpleDBClients` folder, press `Ctrl+Shift+B` and run
**Compile SimpleDBClients**. The task is defined in `.vscode/tasks.json`. It
compiles all Java files under `src` into `bin`, using the compiled engine as the
classpath.

You can also run the task through **Terminal → Run Build Task**.

## Manual compilation

From the `SimpleDBClients` folder, run this in a PowerShell terminal:

```powershell
New-Item -ItemType Directory -Force bin | Out-Null
$files = Get-ChildItem -Recurse -Filter *.java src | ForEach-Object { $_.FullName }
javac -cp "../SimpleDBEngine/bin" -d bin $files
```

On macOS or Linux, run:

```bash
mkdir -p bin
find src -name '*.java' -print0 | xargs -0 javac -cp ../SimpleDBEngine/bin -d bin
```

The `-cp` option tells `javac` where to find the compiled SimpleDBEngine
classes. The `-d bin` option places the compiled client classes in `bin`.

Compilation warnings do not stop the build. Red compiler errors must be fixed
before the affected client classes can be used.
