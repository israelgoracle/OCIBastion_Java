param([switch]$Test)
$ErrorActionPreference = 'Stop'
$projectDir = $PSScriptRoot
$javaBin = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin' } else { '' }
$javac = if ($javaBin) { Join-Path $javaBin 'javac.exe' } else { 'javac' }
$jar = if ($javaBin) { Join-Path $javaBin 'jar.exe' } else { 'jar' }
$java = if ($javaBin) { Join-Path $javaBin 'java.exe' } else { 'java' }
$classes = Join-Path $projectDir 'build/classes'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$sources = @(Get-ChildItem -LiteralPath (Join-Path $projectDir 'src/main/java/ocitools') -Filter '*.java' | ForEach-Object { $_.FullName })
& $javac -encoding UTF-8 -source 8 -target 8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
& $jar cfe (Join-Path $projectDir 'build/OCIBastion_Java.jar') ocitools.Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'JAR packaging failed' }
if ($Test) {
    $testClasses = Join-Path $projectDir 'build/test-classes'
    New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
    $testSources = @(Get-ChildItem -LiteralPath (Join-Path $projectDir 'src/test/java/ocitools') -Filter '*.java' | ForEach-Object { $_.FullName })
    & $javac -encoding UTF-8 -source 8 -target 8 -cp $classes -d $testClasses $testSources
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
    & $java '-Djava.awt.headless=true' -cp "$classes;$testClasses" ocitools.TestSuite $projectDir
    if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
}
Write-Host "Built $(Join-Path $projectDir 'build/OCIBastion_Java.jar')"
