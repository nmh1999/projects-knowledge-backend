$ErrorActionPreference = 'Stop'
$baseDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java.exe' }
$multiModule = "-Dmaven.multiModuleProjectDirectory=$baseDir"
$wrapperJar = Join-Path $baseDir '.mvn\wrapper\maven-wrapper.jar'
& $javaExe $multiModule -cp $wrapperJar 'org.apache.maven.wrapper.MavenWrapperMain' @args
exit $LASTEXITCODE
