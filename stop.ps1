$ErrorActionPreference = 'Stop'

$listeners = @(Get-NetTCPConnection -LocalPort 8083 -State Listen -ErrorAction SilentlyContinue)
if ($listeners.Count -eq 0) {
    Write-Host 'CityAI Hub 未在 8083 端口运行。'
    exit 0
}

$stopped = $false
foreach ($processId in @($listeners.OwningProcess | Select-Object -Unique)) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
    if (-not $process -or $process.Name -notin @('java.exe', 'javaw.exe') -or
        $process.CommandLine -notmatch 'com\.hmdp\.HmDianPingApplication(?:\s|$)') {
        Write-Warning "端口 8083 由其他程序占用（PID $processId），未停止。"
        continue
    }

    Stop-Process -Id $processId -ErrorAction Stop
    Write-Host "已停止 CityAI Hub（PID $processId）。"
    $stopped = $true
}

if (-not $stopped) { exit 1 }
