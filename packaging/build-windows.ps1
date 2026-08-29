[CmdletBinding()]
param(
    [string]$FrontendPath = (Join-Path $PSScriptRoot '..\..\frontend'),
    [string]$JdkPath = $env:JAVA_HOME,
    [string]$NodePath = '',
    [string]$WixPath = '',
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\release'),
    [string]$Version = '1.0.0',
    [switch]$SkipTests,
    [switch]$SkipInstaller,
    [switch]$SkipDesktopCopy
)

$ErrorActionPreference = 'Stop'
$backendPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$frontendPath = (Resolve-Path $FrontendPath).Path
$outputPath = [System.IO.Path]::GetFullPath($OutputPath)

function Invoke-Checked {
    param(
        [Parameter(Mandatory)] [string]$Command,
        [Parameter(Mandatory)] [string[]]$Arguments,
        [Parameter(Mandatory)] [string]$WorkingDirectory
    )
    Push-Location $WorkingDirectory
    try {
        & $Command @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "Command failed with exit code ${LASTEXITCODE}: $Command $($Arguments -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Assert-ChildPath {
    param(
        [Parameter(Mandatory)] [string]$Parent,
        [Parameter(Mandatory)] [string]$Child
    )
    $parentFull = [System.IO.Path]::GetFullPath($Parent).TrimEnd('\') + '\'
    $childFull = [System.IO.Path]::GetFullPath($Child)
    if (-not $childFull.StartsWith($parentFull, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify a path outside its expected parent: $childFull"
    }
}

if (-not $JdkPath) {
    throw 'JDK 21 was not found. Set JAVA_HOME or pass -JdkPath.'
}
$jpackage = Join-Path $JdkPath 'bin\jpackage.exe'
if (-not (Test-Path -LiteralPath $jpackage)) {
    throw "jpackage.exe was not found under $JdkPath"
}
$env:JAVA_HOME = [System.IO.Path]::GetFullPath($JdkPath)
$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$npmArguments = @()
if ($NodePath) {
    $nodePath = (Resolve-Path $NodePath).Path
    $npmCli = Join-Path (Split-Path $npm -Parent) 'node_modules\npm\bin\npm-cli.js'
    if (-not (Test-Path -LiteralPath $npmCli)) { throw "npm-cli.js was not found beside $npm" }
    $npm = $nodePath
    $npmArguments = @($npmCli)
}
$maven = Join-Path $backendPath 'mvnw.cmd'
$frontendBuild = Join-Path $backendPath 'target\frontend-build'
$frontendOutput = Join-Path $frontendBuild 'dist\frontend\browser'
$generatedStatic = Join-Path $backendPath 'target\generated-resources\static'
$packageInput = Join-Path $backendPath 'target\package-input'
$jarName = 'projects-knowledge.jar'
$appName = 'ProjectsKnowledge'
Assert-ChildPath -Parent (Join-Path $backendPath 'target') -Child $generatedStatic
Assert-ChildPath -Parent (Join-Path $backendPath 'target') -Child $packageInput
Assert-ChildPath -Parent (Join-Path $backendPath 'target') -Child $frontendBuild

Invoke-Checked -Command $maven -Arguments @('clean') -WorkingDirectory $backendPath
New-Item -ItemType Directory -Force -Path $frontendBuild | Out-Null
foreach ($file in @('package.json', 'package-lock.json', 'angular.json', 'tsconfig.json', 'tsconfig.app.json')) {
    Copy-Item -LiteralPath (Join-Path $frontendPath $file) -Destination $frontendBuild
}
Copy-Item -LiteralPath (Join-Path $frontendPath 'src') -Destination $frontendBuild -Recurse
Copy-Item -LiteralPath (Join-Path $frontendPath 'public') -Destination $frontendBuild -Recurse
Invoke-Checked -Command $npm -Arguments ($npmArguments + @('ci')) -WorkingDirectory $frontendBuild
Invoke-Checked -Command $npm -Arguments ($npmArguments + @('run', 'build')) -WorkingDirectory $frontendBuild
if (-not (Test-Path -LiteralPath (Join-Path $frontendOutput 'index.html'))) {
    throw "Angular output was not found at $frontendOutput"
}
New-Item -ItemType Directory -Force -Path $generatedStatic | Out-Null
Copy-Item -Path (Join-Path $frontendOutput '*') -Destination $generatedStatic -Recurse -Force

$mavenArguments = if ($SkipTests) { @('-DskipTests', 'package') } else { @('package') }
Invoke-Checked -Command $maven -Arguments $mavenArguments -WorkingDirectory $backendPath
$backendJar = Get-ChildItem -LiteralPath (Join-Path $backendPath 'target') -Filter 'backend-*.jar' -File |
    Where-Object { $_.Name -notlike '*.original' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $backendJar) {
    throw 'The packaged Spring Boot JAR was not found.'
}

if (Test-Path -LiteralPath $packageInput) { Remove-Item -LiteralPath $packageInput -Recurse -Force }
New-Item -ItemType Directory -Force -Path $packageInput | Out-Null
Copy-Item -LiteralPath $backendJar.FullName -Destination (Join-Path $packageInput $jarName)
New-Item -ItemType Directory -Force -Path $outputPath | Out-Null
$appImage = Join-Path $outputPath $appName
$portableZip = Join-Path $outputPath "ProjectsKnowledge-Portable-$Version.zip"
Assert-ChildPath -Parent $outputPath -Child $appImage
Assert-ChildPath -Parent $outputPath -Child $portableZip
if (Test-Path -LiteralPath $appImage) { Remove-Item -LiteralPath $appImage -Recurse -Force }
if (Test-Path -LiteralPath $portableZip) { Remove-Item -LiteralPath $portableZip -Force }

$commonArguments = @(
    '--name', $appName,
    '--app-version', $Version,
    '--vendor', 'Projects Knowledge',
    '--description', 'Local repository knowledge explorer powered by Codex',
    '--input', $packageInput,
    '--main-jar', $jarName,
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    '--arguments', '--projects-knowledge.desktop.enabled=true'
)
Invoke-Checked -Command $jpackage -Arguments (@('--type', 'app-image', '--dest', $outputPath) + $commonArguments) -WorkingDirectory $backendPath
Compress-Archive -LiteralPath $appImage -DestinationPath $portableZip -CompressionLevel Optimal

if (-not $SkipInstaller) {
    if ($WixPath) {
        $wixPath = (Resolve-Path $WixPath).Path
        $env:Path = "$wixPath;$env:Path"
    }
    if ((Get-Command candle.exe -ErrorAction SilentlyContinue) -and (Get-Command light.exe -ErrorAction SilentlyContinue)) {
        $setup = Join-Path $outputPath "ProjectsKnowledge-Setup-$Version.exe"
        Assert-ChildPath -Parent $outputPath -Child $setup
        if (Test-Path -LiteralPath $setup) { Remove-Item -LiteralPath $setup -Force }
        $existingInstallers = @(Get-ChildItem -LiteralPath $outputPath -Filter '*.exe' -File)
        Invoke-Checked -Command $jpackage -Arguments (
            @(
                '--type', 'exe',
                '--dest', $outputPath,
                '--win-per-user-install',
                '--win-dir-chooser',
                '--win-menu',
                '--win-menu-group', 'Projects Knowledge',
                '--win-shortcut',
                '--win-upgrade-uuid', '9bac6ef6-bbba-4744-9c4b-75174fc7bc90'
            ) +
            $commonArguments
        ) -WorkingDirectory $backendPath
        $installer = Get-ChildItem -LiteralPath $outputPath -Filter '*.exe' -File |
            Where-Object { $existingInstallers.FullName -notcontains $_.FullName } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($installer) {
            Rename-Item -LiteralPath $installer.FullName -NewName "ProjectsKnowledge-Setup-$Version.exe"
        }
    } else {
        Write-Warning 'WiX candle.exe/light.exe were not found. Portable ZIP was built; pass -WixPath to build Setup.exe.'
    }
}

Write-Host "Portable application: $portableZip"
$setup = Join-Path $outputPath "ProjectsKnowledge-Setup-$Version.exe"
if (Test-Path -LiteralPath $setup) { Write-Host "Windows installer:   $setup" }

if (-not $SkipDesktopCopy) {
    $desktopPath = [Environment]::GetFolderPath('Desktop')
    if ($desktopPath -and (Test-Path -LiteralPath $desktopPath -PathType Container)) {
        $desktopPortable = Join-Path $desktopPath (Split-Path $portableZip -Leaf)
        Copy-Item -LiteralPath $portableZip -Destination $desktopPortable -Force
        Write-Host "Desktop copy:        $desktopPortable"
    } else {
        Write-Warning 'The Windows Desktop folder was not found; the release copy is still available.'
    }
}
