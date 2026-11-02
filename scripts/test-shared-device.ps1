param(
    [Parameter(Mandatory=$true)][string]$Adb,
    [string]$EvidenceName = 'shared-device-regression',
    [string]$Serial,
    [switch]$Require16Kb,
    [switch]$IncludeMeeting,
    [ValidateRange(1,5)][int]$TimeoutMultiplier = 1
)
$ErrorActionPreference = 'Stop'
if ($EvidenceName -notmatch '^[a-z0-9-]+$') { throw 'EvidenceName must contain lowercase letters, digits and hyphens' }
$repoRoot = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $repoRoot ".arkui-x-validation/application/$EvidenceName"
if (Test-Path -LiteralPath $evidence) { throw 'Choose a new evidence name; previous results are preserved' }
$adbPath = (Resolve-Path -LiteralPath $Adb).Path
$devices = & $adbPath devices
$connected = @($devices | ForEach-Object { if ($_ -match '^(\S+)\s+device$') { $Matches[1] } })
if ($Serial) {
    if ($Serial -notin $connected) { throw 'The selected test device is not connected and authorized' }
} elseif ($connected.Count -eq 1) { $Serial = $connected[0] }
else { throw 'Select a connected test device with -Serial' }
$deviceArguments = @('-s', $Serial)
$policy = & $adbPath @deviceArguments shell dumpsys window policy
if ($policy -match '^\s+showing=true$') { throw 'Unlock the test phone manually before running UI fixtures' }
New-Item -ItemType Directory -Path $evidence | Out-Null
$pageSize = (& $adbPath @deviceArguments shell getconf PAGE_SIZE).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine kernel page size' }
$machine = (& $adbPath @deviceArguments shell uname -m).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot determine kernel architecture' }
$linkerCompat = (& $adbPath @deviceArguments shell getprop bionic.linker.16kb.app_compat.enabled).Trim()
$packageCompatDisabled = (& $adbPath @deviceArguments shell getprop pm.16kb.app_compat.disabled).Trim()
@{ serial=$Serial; pageSize=$pageSize; machine=$machine; linkerCompat=$linkerCompat; packageCompatDisabled=$packageCompatDisabled; timeoutMultiplier=$TimeoutMultiplier } |
    ConvertTo-Json | Set-Content (Join-Path $evidence 'environment.json')
if ($Require16Kb -and ($pageSize -ne '16384' -or $machine -ne 'aarch64' -or
    $linkerCompat -notin @('false','fatal') -or $packageCompatDisabled -ne 'true')) {
    throw '16 KB acceptance requires an aarch64 kernel with PAGE_SIZE=16384 and both compatibility controls disabled'
}
foreach ($relative in @('shared-app/build/outputs/apk/debug/shared-app-debug.apk', 'shared-app/build/outputs/apk/androidTest/debug/shared-app-debug-androidTest.apk')) {
    $apk = (Resolve-Path -LiteralPath (Join-Path $repoRoot $relative)).Path
    & $adbPath @deviceArguments install -r $apk *>> (Join-Path $evidence 'install.log')
    if ($LASTEXITCODE -ne 0) { throw "Installation failed: $relative" }
}
# These UI fixtures replace preference files before launch. A cold target process
# is required between groups so ArkTS singleton settings cannot outlive a fixture.
$groups = @(
    @{ Name='native'; Count=6; Classes='com.clawmate.shared.NativeCapabilitiesDeviceTest' },
    @{ Name='chat'; Count=1; Classes='com.clawmate.shared.ChatContextDeviceTest' },
    @{ Name='workflow'; Count=1; Classes='com.clawmate.shared.WorkflowModelDeviceTest#actualTaskConfirmationCancellationAndUnavailableModelNeverWriteSuccessfulSummary' }
)
# Meeting integration is the final migration phase and opt-in for this driver.
if ($IncludeMeeting) {
    $groups += @{ Name='meeting'; Count=1; Classes='com.clawmate.shared.MeetingIntegrationDeviceTest' }
}
$checkCount = ($groups | Measure-Object -Property Count -Sum).Sum
try { foreach ($group in $groups) {
    & $adbPath @deviceArguments shell am force-stop com.clawmate.shared
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stop the target test process' }
    & $adbPath @deviceArguments shell am force-stop com.clawmate.shared.test
    if ($LASTEXITCODE -ne 0) { throw 'Unable to stop the previous test helper process' }
    $file = Join-Path $evidence ($group.Name + '.log')
    & $adbPath @deviceArguments shell am instrument -w -r -e deviceTimeoutMultiplier $TimeoutMultiplier -e class $group.Classes com.clawmate.shared.test/androidx.test.runner.AndroidJUnitRunner *> $file
    $adbExit = $LASTEXITCODE
    $result = Get-Content -LiteralPath $file -Raw
    $expected = 'OK \(' + $group.Count + ' tests?\)'
    if ($adbExit -ne 0 -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' -or $result -notmatch $expected) { throw "Device group failed: $($group.Name); inspect $file" }
    Write-Output "$($group.Name): $($group.Count) passed"
} } finally {
    # Fixtures restored the original files; discard their cached ArkTS settings.
    & $adbPath @deviceArguments shell am force-stop com.clawmate.shared
    & $adbPath @deviceArguments shell am force-stop com.clawmate.shared.test
}
Write-Output "$checkCount device checks passed in isolated target processes; meeting included: $($IncludeMeeting.IsPresent); no real cloud-model quality claim."
