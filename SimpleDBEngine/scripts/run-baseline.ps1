# Run from any directory. Keep classes, logs, and databases for inspection.
$ErrorActionPreference = 'Stop'

if ($args.Count -ne 0) {
    Write-Error 'Usage: powershell -File scripts/run-baseline.ps1'
    exit 2
}

try {
    $projectDir = Split-Path -Parent $PSScriptRoot
    $java = (Get-Command java -CommandType Application -ErrorAction Stop).Source
    $javac = (Get-Command javac -CommandType Application -ErrorAction Stop).Source
    $runDir = Join-Path ([System.IO.Path]::GetTempPath()) (
        'simpledb-baseline-' + [guid]::NewGuid().ToString('N'))
    $classesDir = Join-Path $runDir 'classes'
    $logsDir = Join-Path $runDir 'logs'
    New-Item -ItemType Directory -Path $classesDir, $logsDir -Force | Out-Null
    Write-Host "Baseline artifacts: $runDir"

    # Windows PowerShell treats native stderr specially; use cmd redirection
    # so Java warnings and version output are captured without aborting the run.
    function Invoke-LoggedJavaTool {
        param([string]$Executable, [string[]]$ToolArguments, [string]$LogPath)
        $quotedArguments = @($ToolArguments | ForEach-Object { '"' + $_ + '"' })
        $command = '""' + $Executable + '" ' + ($quotedArguments -join ' ') +
            ' > "' + $LogPath + '" 2>&1"'
        & $env:ComSpec /d /s /c $command
        return $LASTEXITCODE
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
        'simpledb.index.TypedIndexIntegrationTest'
        'simpledb.materialize.OrderByIntegrationTest'
        'simpledb.opt.JoinIntegrationTest'
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
