$ErrorActionPreference = 'Stop'
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
& $java -Djava.net.useSystemProxies=true -jar (Join-Path $PSScriptRoot 'build/OCIBastion_Java.jar')
exit $LASTEXITCODE
