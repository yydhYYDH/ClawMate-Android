[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidatePattern('^[a-zA-Z0-9._-]+$')][string]$Serial,
    [string]$Adb,
    [ValidateRange(1, 65535)][int]$Port = 8787,
    [switch]$Watch
)

if (-not $Adb) {
    foreach ($sdk in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not $sdk) { continue }
        $candidate = Join-Path $sdk 'platform-tools/adb.exe'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $Adb = $candidate
            break
        }
    }
    if (-not $Adb) { $Adb = 'adb' }
}
$adbExe = (Get-Command -Name $Adb -CommandType Application -ErrorAction Stop).Source
$endpoint = "tcp:$Port"
$mappingPattern = "^\s*\S+\s+$([regex]::Escape($endpoint))\s+(\S+)\s*$"
$previous = ''

do {
    $mappings = & $adbExe -s $Serial reverse --list 2>$null
    $ready = $false
    if ($LASTEXITCODE -ne 0) {
        $status = "Waiting for ADB device $Serial."
    } else {
        $mapping = $mappings | Select-String -Pattern $mappingPattern | Select-Object -First 1
        if ($mapping) {
            $destination = $mapping.Matches[0].Groups[1].Value
            $ready = $destination -eq $endpoint
            $status = if ($ready) { "USB reverse active on $endpoint." }
                else { "$endpoint is already forwarded to $destination." }
        } else {
            & $adbExe -s $Serial reverse --no-rebind $endpoint $endpoint 2>$null | Out-Null
            $ready = $LASTEXITCODE -eq 0
            $status = if ($ready) { "Restored USB reverse on $endpoint." }
                else { "Could not create USB reverse on $endpoint." }
        }
    }
    if ($status -ne $previous) {
        Write-Output ("{0:HH:mm:ss} {1}" -f (Get-Date), $status)
        $previous = $status
    }
    if (-not $Watch) {
        if (-not $ready) { exit 1 }
        break
    }
    Start-Sleep -Seconds 3
} while ($Watch)
