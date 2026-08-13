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

The client source code is under `src`, organized into the `embedded` and
`network` packages. Embedded clients use a local database; network clients
connect to a running SimpleDB server.

## Open the project

Open the `SimpleDBClients` folder itself in VS Code:

```text
CS3223-SimpleDB-Project/SimpleDBClients
```

Do not create another nested `SimpleDBClients` folder.

The file `.vscode/settings.json` configures `src` as the source folder, `bin`
as the output folder, and JAR files under `lib` as referenced libraries. The
SimpleDBEngine build task creates `lib/simpledb-engine.jar`, allowing the client
code and VS Code IntelliSense to resolve classes from the engine.

## Compile the projects

Compile `SimpleDBEngine` before compiling the clients. From the
`SimpleDBEngine` folder, press `Ctrl+Shift+B` and run **Compile SimpleDBEngine**.
This also copies the generated engine JAR to `SimpleDBClients/lib`.

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

## Run the embedded clients

Embedded clients connect directly to a local database and do not require the
SimpleDB server. Run them from the `SimpleDBClients` project using the **Run**
link above each class's `main` method:

```text
embedded.CreateStudentDB
embedded.StudentMajor
embedded.ChangeMajor
embedded.FindMajors
```

The embedded clients use the `studentdb` database in their current working
directory. Run `embedded.CreateStudentDB` once before the other clients. Do not
rerun it against an existing database; delete the database directory first if
you need to recreate it.

## Run the network clients

Network clients connect to `jdbc:simpledb://localhost`, so the SimpleDB server
must be running first.

1. Open `SimpleDBEngine` in VS Code.
2. Start **SimpleDB Server** from **Run and Debug**.
3. Open `SimpleDBClients`.
4. Compile the clients if necessary.
5. Run the clients in this order:

   ```text
   network.CreateStudentDB
   network.StudentMajor
   network.ChangeMajor
   network.FindMajors
   ```

`network.FindMajors` prompts for a department name such as `drama`. If the
server is stopped, the network clients will report a connection error. Restart
the server before running them again. The network database is separate from the
embedded database, even though both are normally named `studentdb`.

## Run SimpleIJ

`SimpleIJ` is an interactive JDBC client. Run it from the `SimpleDBClients`
project using the **Run** link above its `main` method. It first prompts for a
connection string and then accepts SQL commands.

For the embedded database, enter:

```text
jdbc:simpledb:studentdb
```

For the network database, start the **SimpleDB Server** first, then enter:

```text
jdbc:simpledb://localhost
```

At the `SQL>` prompt, enter SQL statements such as:

```sql
select SName, DName from DEPT, STUDENT where MajorId = DId
```

Enter `exit` to close the client. The embedded connection uses the database in
the client's current working directory, while the network connection uses the
database managed by the running server.
