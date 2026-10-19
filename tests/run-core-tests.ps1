param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$compiler = Get-Command javac.exe -ErrorAction Stop
$runtime = Get-Command java.exe -ErrorAction Stop
$rootPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
Push-Location -LiteralPath $rootPath
try {
    $classesPath = Join-Path $rootPath 'work\core-test-classes'
    New-Item -ItemType Directory -Path $classesPath -Force | Out-Null
    & $compiler.Source -encoding UTF-8 --release 8 -classpath . '-Xlint:all,-options' -d $classesPath `
        'app/src/com/langyu/assistant/GameState.java' `
        'app/src/com/langyu/assistant/RuleEngine.java' 'tests/CoreTests.java'
    if ($LASTEXITCODE -ne 0) { throw "Core test compilation failed ($LASTEXITCODE)" }
    & $runtime.Source -cp $classesPath CoreTests
    if ($LASTEXITCODE -ne 0) { throw "Core behavior checks failed ($LASTEXITCODE)" }
} finally {
    Pop-Location
}
