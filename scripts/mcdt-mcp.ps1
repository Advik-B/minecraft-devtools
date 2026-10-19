# scripts/mcdt-mcp.ps1
# MCDT Model Context Protocol (MCP) Stdio Shim in PowerShell
param(
    [string]$BaseUrl,
    [string]$Token,
    [int]$Port
)

$ErrorActionPreference = "Stop"

if (-not $BaseUrl -or -not $Token) {
    $discoveryFiles = @()
    $userInstances = Join-Path $env:USERPROFILE ".mcdt\instances"
    if (Test-Path $userInstances) {
        $discoveryFiles += Get-ChildItem -Path $userInstances -Filter "*.json" | Sort-Object LastWriteTime -Descending
    }
    $runConfig = Join-Path $PSScriptRoot "..\run\config\mcdt\instance.json"
    if (Test-Path $runConfig) {
        $discoveryFiles += Get-Item $runConfig
    }

    if ($discoveryFiles.Count -eq 0) {
        [Console]::Error.WriteLine("Error: No active MCDT instance found.")
        exit 1
    }

    $instance = Get-Content -Raw $discoveryFiles[0].FullName | ConvertFrom-Json
    $BaseUrl = if ($instance.baseUrl) { $instance.baseUrl } else { $instance.url }
    $Token = $instance.token
}

$postUrl = "$BaseUrl/mcp"

while ($null -ne ($line = [Console]::ReadLine())) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0) { continue }

    try {
        $headers = @{
            "Authorization" = "Bearer $Token"
            "Content-Type" = "application/json"
            "Accept" = "application/json"
        }
        $resp = Invoke-RestMethod -Uri $postUrl -Method Post -Headers $headers -Body $trimmed -TimeoutSec 10
        $json = $resp | ConvertTo-Json -Depth 10 -Compress
        [Console]::WriteLine($json)
    } catch {
        $errObj = @{
            jsonrpc = "2.0"
            error = @{
                code = -32603
                message = $_.Exception.Message
            }
            id = $null
        }
        [Console]::WriteLine(($errObj | ConvertTo-Json -Compress))
    }
}
