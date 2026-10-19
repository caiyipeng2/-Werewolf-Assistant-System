param([string]$ProjectRoot = (Split-Path $PSScriptRoot -Parent))
$ErrorActionPreference = 'Stop'
$compiler = Get-Command javac.exe -ErrorAction Stop
$runtime = Get-Command java.exe -ErrorAction Stop
$rootPath = (Resolve-Path -LiteralPath $ProjectRoot).Path
Push-Location -LiteralPath $rootPath
try {
    $classesPath = Join-Path $rootPath 'work\screen-test-classes'
    New-Item -ItemType Directory -Path $classesPath -Force | Out-Null
    # Only the Android/repository boundary is stubbed; parser, binder and state below are production code.
    $stubRoot = Join-Path $rootPath 'work\screen-test-stubs'
    New-Item -ItemType Directory -Path "$stubRoot\android\content", "$stubRoot\android\os", "$stubRoot\com\langyu\assistant" -Force | Out-Null
    'package android.content; public class Context { }' | Set-Content -Encoding Ascii "$stubRoot\android\content\Context.java"
    'package android.os; public class Looper { private static final Looper MAIN=new Looper(); public static Looper getMainLooper(){return MAIN;} public static Looper myLooper(){return MAIN;} }' | Set-Content -Encoding Ascii "$stubRoot\android\os\Looper.java"
    @'
package com.langyu.assistant;
import android.content.Context; import java.util.*;
public final class Repository {
  public GameState state=new GameState();
  public static Repository get(Context c){return new Repository();}
  public List<Board> boards(){return Collections.emptyList();}
  public void save(){} public void archive(){}
  public static final class Board { public String id,name; public String[] roles; public int wolves; }
}
'@ | Set-Content -Encoding Ascii "$stubRoot\com\langyu\assistant\Repository.java"
    & $compiler.Source -encoding UTF-8 --release 8 -classpath . '-Xlint:all,-options' -d $classesPath `
        "$stubRoot\android\content\Context.java" "$stubRoot\android\os\Looper.java" "$stubRoot\com\langyu\assistant\Repository.java" `
        'app/src/com/langyu/assistant/GameState.java' 'app/src/com/langyu/assistant/RuleEngine.java' `
        'app/src/com/langyu/assistant/SessionBinder.java' `
        'app/src/com/langyu/assistant/ScreenRecognition.java' `
        'tests/ScreenRecognitionTests.java'
    if ($LASTEXITCODE -ne 0) { throw "Screen test compilation failed ($LASTEXITCODE)" }
    $catalog = Get-Content -LiteralPath 'app/assets/boards.json' -Raw -Encoding UTF8 | ConvertFrom-Json
    $catalogRows = foreach ($board in $catalog) { "$($board.id)`t$($board.name)`t$($board.wolves)`t$($board.roles -join ',')" }
    $catalogPath = Join-Path $stubRoot 'board-catalog.tsv'
    [System.IO.File]::WriteAllLines($catalogPath, [string[]]$catalogRows, (New-Object System.Text.UTF8Encoding($false)))
    & $runtime.Source -cp $classesPath com.langyu.assistant.ScreenRecognitionTests $catalogPath
    if ($LASTEXITCODE -ne 0) { throw "Screen behavior checks failed ($LASTEXITCODE)" }
} finally { Pop-Location }
