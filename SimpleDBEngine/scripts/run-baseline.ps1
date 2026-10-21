# Run from any directory. Keep classes, logs, and databases for inspection.
$ErrorActionPreference = 'Stop'

if ($args.Count -ne 0) {
    Write-Error 'Usage: powershell -File scripts/run-baseline.ps1'
    exit 2
}

try {
    $projectDir = Split-Path -Parent $PSScriptRoot
    # Get-Command can return several installations. ProcessStartInfo needs
    # one executable path, not an array converted to a space-separated string.
    $java = (Get-Command java.exe -CommandType Application -ErrorAction Stop |
        Select-Object -First 1).Source
    $javac = (Get-Command javac.exe -CommandType Application -ErrorAction Stop |
        Select-Object -First 1).Source
    $runDir = Join-Path ([System.IO.Path]::GetTempPath()) (
        'simpledb-baseline-' + [guid]::NewGuid().ToString('N'))
    $classesDir = Join-Path $runDir 'classes'
    $logsDir = Join-Path $runDir 'logs'
    New-Item -ItemType Directory -Path $classesDir, $logsDir -Force | Out-Null
    Write-Host "Baseline artifacts: $runDir"
    Write-Host "Java: $java"
    Write-Host "Compiler: $javac"

    # Launch Java directly so executable paths containing spaces work, and
    # capture stderr without Windows PowerShell treating it as a script error.
    function Invoke-LoggedJavaTool {
        param([string]$Executable, [string[]]$ToolArguments, [string]$LogPath)
        if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
            throw "Executable does not exist: $Executable"
        }
        # ProcessStartInfo.Arguments also works with Windows PowerShell 5.1.
        # Escape quotes and trailing backslashes using Windows argument rules.
        $quotedArguments = @($ToolArguments | ForEach-Object {
            $escaped = $_ -replace '(\\*)"', '$1$1\"'
            $escaped = $escaped -replace '(\\+)$', '$1$1'
            '"' + $escaped + '"'
        })
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $Executable
        $startInfo.Arguments = $quotedArguments -join ' '
        $startInfo.WorkingDirectory = (Get-Location).ProviderPath
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        try {
            try {
                [void]$process.Start()
            } catch {
                throw "Could not launch '$Executable' in '$($startInfo.WorkingDirectory)': $_"
            }
            # Drain both streams concurrently to avoid pipe-buffer deadlocks.
            $stdoutTask = $process.StandardOutput.ReadToEndAsync()
            $stderrTask = $process.StandardError.ReadToEndAsync()
            $process.WaitForExit()
            $stdout = $stdoutTask.GetAwaiter().GetResult()
            $stderr = $stderrTask.GetAwaiter().GetResult()
            [System.IO.File]::WriteAllText($LogPath, $stdout + $stderr)
            return $process.ExitCode
        } finally {
            $process.Dispose()
        }
    }

    $javaVersionLog = Join-Path $runDir 'java-version.log'
    $javacVersionLog = Join-Path $runDir 'javac-version.log'
    $status = Invoke-LoggedJavaTool $java @('-version') $javaVersionLog
    if ($status -ne 0) { throw 'java -version failed.' }
    $status = Invoke-LoggedJavaTool $javac @('-version') $javacVersionLog
    if ($status -ne 0) { throw 'javac -version failed.' }

    $sources = @(Get-ChildItem -LiteralPath (Join-Path $projectDir 'src') -Recurse -Filter '*.java' -File)
    if ($sources.Count -eq 0) { throw 'No Java sources found.' }
    # An argument file avoids the Windows command-line length limit. Java
    # accepts forward slashes in quoted paths; UTF-8 without BOM works on JDK 25.
    $sourceList = Join-Path $runDir 'sources.txt'
    $sourceLines = @($sources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
    [System.IO.File]::WriteAllLines($sourceList, $sourceLines,
        (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Compiling $($sources.Count) Java sources..."
    $compileLog = Join-Path $logsDir 'compile.log'
    $status = Invoke-LoggedJavaTool $javac @('-d', $classesDir, ('@' + $sourceList)) $compileLog
    if ($status -ne 0) {
        Get-Content -LiteralPath $compileLog | Write-Host
        throw 'Compilation failed.'
    }
    Write-Host 'Compilation passed.'

    $tests = @(
        'simpledb.query.TermTest'
        'simpledb.parse.CreateIndexParserTest'
        'simpledb.parse.OrderByParserTest'
        'simpledb.parse.AggregateParserTest'
        'simpledb.parse.DistinctParserTest'
        'simpledb.parse.ExplainParserTest'
        'simpledb.parse.QueryValidationParserTest'
        'simpledb.plan.QueryValidationIntegrationTest'
        'simpledb.index.TypedIndexIntegrationTest'
        'simpledb.materialize.OrderByIntegrationTest'
        'simpledb.opt.JoinIntegrationTest'
        'simpledb.opt.JoinPolicyIntegrationTest'
        'simpledb.test.JoinFeatureIntegrationTest'
        'simpledb.test.QueryResourceIntegrationTest'
        'simpledb.test.IndexedUpdateAggregateIntegrationTest'
        'simpledb.test.AggregateSemanticsIntegrationTest'
        'simpledb.test.TimeTypeIntegrationTest'
        'simpledb.test.TimeTypeRegressionTest'
        'simpledb.multibuffer.HashJoinIntegrationTest'
        'simpledb.materialize.AggregateIntegrationTest'
        'simpledb.materialize.DistinctPlanTest'
        'simpledb.materialize.DistinctQueryIntegrationTest'
        'simpledb.plan.PlanExplainerTest'
        'simpledb.plan.ExplainIntegrationTest'
    )
    $passed = 0
    $failed = 0
    foreach ($testClass in $tests) {
        $workDir = Join-Path (Join-Path $runDir 'work') $testClass
        $tempDir = Join-Path $workDir 'tmp'
        New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
        $logFile = Join-Path $logsDir ($testClass + '.log')
        Push-Location -LiteralPath $workDir
        try {
            $status = Invoke-LoggedJavaTool $java @('-ea', "-Djava.io.tmpdir=$tempDir", '-cp', $classesDir, $testClass) $logFile
        } finally {
            Pop-Location
        }
        if ($status -eq 0) {
            $passed++
            Write-Host "PASS $testClass"
            Get-Content -LiteralPath $logFile | Where-Object { $_ -like 'All *passed.*' } |
                ForEach-Object { Write-Host "  $_" }
        } else {
            $failed++
            Write-Host "FAIL $testClass (exit $status)"
            Get-Content -LiteralPath $logFile | Write-Host
        }
    }
    Write-Host "`nBaseline: $passed passed, $failed failed, $($tests.Count) total."
    Write-Host "Classes, compiler output, test logs, and isolated databases: $runDir"
    if ($failed -ne 0) { exit 1 }
} catch {
    Write-Host "Baseline failed: $_"
    if ($runDir) { Write-Host "Artifacts: $runDir" }
    exit 1
}
exit 0
