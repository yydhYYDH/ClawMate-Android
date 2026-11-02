param(
    [Parameter(Mandatory=$true)][string]$AndroidSdk,
    [string]$EvidenceName = 'meeting-native-smoke',
    [string]$Serial,
    [switch]$Require16Kb
)
$ErrorActionPreference = 'Stop'
if ($EvidenceName -notmatch '^[a-z0-9-]+$') { throw 'Use a new lowercase evidence name' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $repoRoot ".arkui-x-validation/application/$EvidenceName"
if (Test-Path -LiteralPath $evidence) { throw 'Previous evidence must be preserved' }
New-Item -ItemType Directory -Path $evidence | Out-Null
$sdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
$adb = Join-Path $sdk 'platform-tools/adb.exe'
$devices = & $adb devices
$connected = @($devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
if ($Serial) {
    if ($Serial -notin $connected) { throw 'The selected test device is not connected and authorized' }
} elseif ($connected.Count -eq 1) { $Serial = $connected[0] }
else { throw 'Select a connected test device with -Serial' }
$deviceArguments = @('-s', $Serial)
$pageSize = (& $adb @deviceArguments shell getconf PAGE_SIZE).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine kernel page size' }
$machine = (& $adb @deviceArguments shell uname -m).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine kernel architecture' }
$linkerCompat = (& $adb @deviceArguments shell getprop bionic.linker.16kb.app_compat.enabled).Trim()
$packageCompatDisabled = (& $adb @deviceArguments shell getprop pm.16kb.app_compat.disabled).Trim()
@{ serial=$Serial; pageSize=$pageSize; machine=$machine; linkerCompat=$linkerCompat; packageCompatDisabled=$packageCompatDisabled } |
    ConvertTo-Json | Set-Content (Join-Path $evidence 'environment.json')
if ($Require16Kb -and ($pageSize -ne '16384' -or $machine -ne 'aarch64' -or
    $linkerCompat -notin @('false','fatal') -or $packageCompatDisabled -ne 'true')) {
    throw '16 KB acceptance requires an aarch64 kernel with PAGE_SIZE=16384 and both compatibility controls disabled'
}
$compiler = Join-Path $sdk 'ndk/25.2.9519653/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang.cmd'
$executable = Join-Path $evidence 'complex-math-smoke'
& $compiler -O2 -fno-builtin (Join-Path $repoRoot 'tests/meeting-complex-math-smoke.c') -ldl -lm '-Wl,-z,max-page-size=16384' '-Wl,-z,common-page-size=16384' -o $executable *> (Join-Path $evidence 'compile.log')
if ($LASTEXITCODE -ne 0) { throw 'Native smoke compilation failed' }
$packagePaths = @(& $adb @deviceArguments shell pm path com.clawmate.shared)
if ($LASTEXITCODE -ne 0) { throw 'Cannot read the installed package path' }
$baseApk = @($packagePaths | Where-Object { $_ -match '^package:/data/app/.+/base.apk$' })
if ($baseApk.Count -ne 1) { throw 'Install the current shared APK before running this check' }
$library = $baseApk[0].Trim().Substring('package:'.Length).Replace('/base.apk','/lib/arm64/libandroid-complex-math.so')
& $adb @deviceArguments shell sha256sum $library *> (Join-Path $evidence 'installed-library-sha256.log')
if ($LASTEXITCODE -ne 0) { throw 'Cannot fingerprint the installed native library' }
$installedHash = (Get-Content (Join-Path $evidence 'installed-library-sha256.log') -Raw).Trim().Split(' ')[0]
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $repoRoot 'shared-app/build/outputs/apk/debug/shared-app-debug.apk'))
try {
    $entry = $archive.GetEntry('lib/arm64-v8a/libandroid-complex-math.so')
    if (-not $entry) { throw 'Current APK has no complex-math library' }
    $stream = $entry.Open()
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $expectedHash = [BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
    finally { $stream.Dispose(); $sha.Dispose() }
    if ($installedHash -ne $expectedHash) { throw 'Installed library differs from the current APK; install it first' }
} finally { $archive.Dispose() }
$remote = '/data/local/tmp/clawmate-complex-math-' + $EvidenceName
try {
    & $adb @deviceArguments push $executable $remote *> (Join-Path $evidence 'push.log')
    if ($LASTEXITCODE -ne 0) { throw 'Native smoke push failed' }
    & $adb @deviceArguments shell chmod 755 $remote
    if ($LASTEXITCODE -ne 0) { throw 'Native smoke chmod failed' }
    & $adb @deviceArguments shell getconf PAGE_SIZE *> (Join-Path $evidence 'page-size.log')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine kernel page size' }
    & $adb @deviceArguments shell $remote $library *> (Join-Path $evidence 'numerical.log')
    $nativeExit = $LASTEXITCODE
    $result = Get-Content (Join-Path $evidence 'numerical.log') -Raw
    if ($nativeExit -ne 0 -or $result -notmatch 'OK 22 complex-math exports; numerical smoke passed') { throw "Native smoke failed: $result" }
    Write-Output $result.Trim()
    Write-Output "Kernel PAGE_SIZE=$((Get-Content (Join-Path $evidence 'page-size.log') -Raw).Trim())"
} finally {
    & $adb @deviceArguments shell rm -f $remote
}
