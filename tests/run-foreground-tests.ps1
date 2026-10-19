param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$compiler = Get-Command javac.exe -ErrorAction Stop
$runtime = Get-Command java.exe -ErrorAction Stop
$rootPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
Push-Location -LiteralPath $rootPath
try {
    $classesPath = Join-Path $rootPath 'work\foreground-test-classes'
    New-Item -ItemType Directory -Path $classesPath -Force | Out-Null
    & $compiler.Source -encoding UTF-8 --release 8 -classpath . '-Xlint:all,-options' -d $classesPath `
        'app/src/com/langyu/assistant/ForegroundTracker.java' 'tests/ForegroundTrackerTests.java'
    if ($LASTEXITCODE -ne 0) { throw "Foreground test compilation failed ($LASTEXITCODE)" }
    & $runtime.Source -cp $classesPath com.langyu.assistant.ForegroundTrackerTests
    if ($LASTEXITCODE -ne 0) { throw "Foreground behavior checks failed ($LASTEXITCODE)" }
} finally { Pop-Location }
