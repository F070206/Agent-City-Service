$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$localConfigPath = Join-Path $PSScriptRoot 'start.local.json'
if (Test-Path -LiteralPath $localConfigPath) {
    $localConfig = Get-Content -LiteralPath $localConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json
    foreach ($name in @('SPRING_DATASOURCE_PASSWORD', 'SPRING_AI_OPENAI_API_KEY', 'CITYAI_ADMIN_TOKEN', 'CITYAI_AUTH_MOCK_CODE_ENABLED')) {
        $value = [string]$localConfig.$name
        if (-not [Environment]::GetEnvironmentVariable($name, 'Process') -and $value) {
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
}
if (-not $env:SPRING_AI_OPENAI_API_KEY) {
    throw '请先在 start.local.json 或环境变量中配置 SPRING_AI_OPENAI_API_KEY；当前项目需要此配置才能启动。'
}

function Test-TcpPort([string]$HostName, [int]$Port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $result = $client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $result.AsyncWaitHandle.WaitOne(2000)) { return $false }
        $client.EndConnect($result)
        return $true
    } catch {
        return $false
    } finally {
        $client.Dispose()
    }
}

function Wait-TcpPort([string]$HostName, [int]$Port, [int]$Seconds = 30) {
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($timer.Elapsed.TotalSeconds -lt $Seconds) {
        if (Test-TcpPort $HostName $Port) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw '未找到 Java。请安装 JDK 17 并配置 PATH。'
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw '未找到 Maven。请安装 Maven 3.9+ 并配置 PATH。'
}

# systemd services alone do not keep a WSL distribution running.
$wslSession = Start-Process -FilePath wsl.exe -ArgumentList '-d', 'Ubuntu-24.04', '--', 'sleep', 'infinity' -WindowStyle Hidden -PassThru
try {
if (-not (Test-TcpPort '127.0.0.1' 6379)) {
    Write-Host '启动 WSL Ubuntu 中的 Redis...'
    & wsl.exe -d Ubuntu-24.04 -u root -- systemctl start redis-server
    if ($LASTEXITCODE -ne 0 -or -not (Wait-TcpPort '127.0.0.1' 6379 15)) {
        throw '无法连接 WSL Redis。请检查 Ubuntu-24.04 中的 redis-server 服务和 WSL 端口转发。'
    }
}

if (-not (Test-TcpPort 'localhost' 9876)) {
    $rocketHome = $env:ROCKETMQ_HOME
    if (-not $rocketHome -or -not (Test-Path (Join-Path $rocketHome 'bin\mqnamesrv.cmd'))) {
        throw '未找到 RocketMQ。请设置 ROCKETMQ_HOME 指向本机安装目录。'
    }
    Write-Host '启动 RocketMQ NameServer...'
    Start-Process -FilePath (Join-Path $rocketHome 'bin\mqnamesrv.cmd') -WorkingDirectory $rocketHome -WindowStyle Hidden
    if (-not (Wait-TcpPort 'localhost' 9876 30)) { throw 'RocketMQ NameServer 未能在 9876 端口启动。' }
}

if (-not (Test-TcpPort 'localhost' 10911)) {
    $rocketHome = $env:ROCKETMQ_HOME
    if (-not $rocketHome -or -not (Test-Path (Join-Path $rocketHome 'bin\mqbroker.cmd'))) {
        throw '未找到 RocketMQ Broker。请检查 ROCKETMQ_HOME。'
    }
    Write-Host '启动 RocketMQ Broker...'
    Start-Process -FilePath (Join-Path $rocketHome 'bin\mqbroker.cmd') -ArgumentList '-c', 'conf\broker.conf' -WorkingDirectory $rocketHome -WindowStyle Hidden
    if (-not (Wait-TcpPort 'localhost' 10911 45)) { throw 'RocketMQ Broker 未能在 10911 端口启动。' }
}

$milvusHost = if ($env:SPRING_AI_MILVUS_HOST) { $env:SPRING_AI_MILVUS_HOST } else { 'localhost' }
$milvusPort = if ($env:SPRING_AI_MILVUS_PORT) { [int]$env:SPRING_AI_MILVUS_PORT } else { 19530 }
if (-not (Test-TcpPort $milvusHost $milvusPort)) {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw '未找到 Docker CLI。' }
    & docker info --format '{{.ServerVersion}}' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop 未运行。请先启动 Docker Desktop。' }
    foreach ($container in @('milvus-etcd', 'milvus-minio', 'milvus-standalone')) {
        $state = & docker inspect --format '{{.State.Running}}' $container 2>$null
        if ($LASTEXITCODE -ne 0) { throw "未找到 Docker 容器 $container。" }
        if ($state -ne 'true') {
            Write-Host "启动 Docker 容器 $container..."
            & docker start $container
            if ($LASTEXITCODE -ne 0) { throw "Docker 容器 $container 启动失败。" }
        }
    }
    if (-not (Wait-TcpPort $milvusHost $milvusPort 90)) {
        throw 'Milvus 未能在 19530 端口启动。请检查 docker logs milvus-standalone 和 docker logs milvus-etcd。'
    }
}

$services = @(
    @{ Name = 'MySQL'; HostName = 'localhost'; Port = 3306 },
    @{ Name = 'Redis'; HostName = '127.0.0.1'; Port = 6379 },
    @{ Name = 'RocketMQ NameServer'; HostName = 'localhost'; Port = 9876 },
    @{ Name = 'Milvus'; HostName = $milvusHost; Port = $milvusPort }
)

$missing = @($services | Where-Object { -not (Test-TcpPort $_.HostName $_.Port) })
if ($missing.Count -gt 0) {
    $missing | ForEach-Object { Write-Host ("未连接到 {0} ({1}:{2})" -f $_.Name, $_.HostName, $_.Port) -ForegroundColor Red }
    if (@($missing | Where-Object Name -eq 'Redis').Count -gt 0) {
        Write-Warning 'WSL 中 Redis 已启动但 Windows 无法访问时，请检查 WSL localhost 转发；后端配置要求 Windows 可连接 127.0.0.1:6379。'
    }
    throw '请先启动上述服务，并按 README.md 初始化 hmdp 数据库。'
}

if (-not $env:SPRING_DATASOURCE_PASSWORD) {
    Write-Warning '未设置 SPRING_DATASOURCE_PASSWORD；仅当 MySQL root 用户无密码时可正常连接。'
}
Write-Host '启动 CityAI Hub，成功后访问 http://localhost:8083 （按 Ctrl+C 停止）'
& mvn spring-boot:run
if ($LASTEXITCODE -ne 0) { throw "Maven 启动失败，退出码：$LASTEXITCODE" }
} finally {
    if (-not $wslSession.HasExited) {
        Stop-Process -Id $wslSession.Id -ErrorAction SilentlyContinue
    }
}
