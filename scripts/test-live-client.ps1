# scripts/test-live-client.ps1
# Automated live test runner executing against an active Minecraft DevTools instance.

param(
    [string]$BaseUrl,
    [string]$Token,
    [switch]$ShutdownClient
)

$ErrorActionPreference = "Stop"

# Auto-discover instance if not provided
if (-not $BaseUrl -or -not $Token) {
    Write-Host "Searching for active MCDT instance discovery file..." -ForegroundColor Cyan
    $discoveryFiles = @()

    $userInstances = Join-Path $env:USERPROFILE ".mcdt\instances"
    if (Test-Path $userInstances) {
        $discoveryFiles += Get-ChildItem -Path $userInstances -Filter "*.json" | Sort-Object LastWriteTime -Descending
    }

    $runConfigInstance = Join-Path $PSScriptRoot "..\run\config\mcdt\instance.json"
    if (Test-Path $runConfigInstance) {
        $discoveryFiles += Get-Item $runConfigInstance
    }

    if ($discoveryFiles.Count -eq 0) {
        Write-Error "No running MCDT instance found. Please launch the client first via './gradlew.bat runClient'."
        exit 1
    }

    $instanceFile = $discoveryFiles[0].FullName
    Write-Host "Found instance discovery file: $instanceFile" -ForegroundColor Green
    $instance = Get-Content -Raw $instanceFile | ConvertFrom-Json

    $BaseUrl = if ($instance.baseUrl) { $instance.baseUrl } else { $instance.url }
    $Token = $instance.token
}

Write-Host "`nTargeting MCDT client at: $BaseUrl" -ForegroundColor Cyan
Write-Host "Bearer token length: $($Token.Length) chars`n" -ForegroundColor DarkGray

$passed = 0
$failed = 0
$results = @()

function Test-Endpoint {
    param(
        [string]$Name,
        [string]$Path,
        [string]$Method = "GET",
        [object]$Body = $null,
        [hashtable]$Headers = @{},
        [int]$ExpectedStatus = 200,
        [scriptblock]$Validator = $null
    )

    $url = "$BaseUrl$Path"
    $allHeaders = @{
        "Authorization" = "Bearer $Token"
    }
    foreach ($k in $Headers.Keys) {
        $allHeaders[$k] = $Headers[$k]
    }

    $bodyStr = $null
    if ($Body -ne $null) {
        if ($Body -is [string]) {
            $bodyStr = $Body
        } else {
            $bodyStr = ($Body | ConvertTo-Json -Depth 10 -Compress)
        }
        $allHeaders["Content-Type"] = "application/json"
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $status = 0
    $response = $null
    $errorMsg = ""
    $respHeaders = @{}
    $rawContent = $null

    try {
        $req = [System.Net.HttpWebRequest]::Create($url)
        $req.Method = $Method
        $req.Timeout = 8000
        foreach ($h in $allHeaders.Keys) {
            if ($h -eq "Content-Type") {
                $req.ContentType = $allHeaders[$h]
            } else {
                $req.Headers.Add($h, $allHeaders[$h])
            }
        }

        if ($bodyStr -ne $null) {
            $bytes = [System.Text.Encoding]::UTF8.GetBytes($bodyStr)
            $req.ContentLength = $bytes.Length
            $reqStream = $req.GetRequestStream()
            $reqStream.Write($bytes, 0, $bytes.Length)
            $reqStream.Close()
        }

        $httpResp = $req.GetResponse()
        $status = [int]$httpResp.StatusCode
        $respStream = $httpResp.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($respStream)
        $rawContent = $reader.ReadToEnd()
        $reader.Close()
        $httpResp.Close()
    } catch [System.Net.WebException] {
        if ($_.Exception.Response -ne $null) {
            $httpResp = $_.Exception.Response
            $status = [int]$httpResp.StatusCode
            $respStream = $httpResp.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($respStream)
            $rawContent = $reader.ReadToEnd()
            $reader.Close()
            $httpResp.Close()
        } else {
            $errorMsg = $_.Exception.Message
        }
    } catch {
        $errorMsg = $_.Exception.Message
    }
    $sw.Stop()

    $isOk = ($status -eq $ExpectedStatus)
    $jsonObj = $null
    if ($rawContent -and $rawContent.StartsWith("{")) {
        try {
            $jsonObj = $rawContent | ConvertFrom-Json
        } catch {}
    }

    if ($isOk -and $Validator -ne $null) {
        try {
            $valResult = & $Validator $jsonObj $rawContent
            if ($valResult -eq $false) {
                $isOk = $false
                $errorMsg = "Custom validator assertion failed"
            }
        } catch {
            $isOk = $false
            $errorMsg = "Validator error: $($_.Exception.Message)"
        }
    }

    if ($isOk) {
        $script:passed++
        Write-Host "  [PASS] $Method $Path ($status) - $($sw.ElapsedMilliseconds)ms - $Name" -ForegroundColor Green
    } else {
        $script:failed++
        Write-Host "  [FAIL] $Method $Path (got $status, expected $ExpectedStatus) - $Name" -ForegroundColor Red
        if ($errorMsg) {
            Write-Host "         Error: $errorMsg" -ForegroundColor Yellow
        }
        if ($rawContent) {
            $snippet = if ($rawContent.Length -gt 160) { $rawContent.Substring(0, 160) + "..." } else { $rawContent }
            Write-Host "         Body: $snippet" -ForegroundColor DarkGray
        }
    }

    return [PSCustomObject]@{
        Name = $Name
        Method = $Method
        Path = $Path
        Status = $status
        Passed = $isOk
        DurationMs = $sw.ElapsedMilliseconds
        Json = $jsonObj
        Raw = $rawContent
    }
}

Write-Host "=== 1. System, Auth & Documentation Tests ===" -ForegroundColor White
$null = Test-Endpoint -Name "Health Check" -Path "/v1/health" -Validator { param($j) $j.mcdtVersion -ne $null -and $j.uptimeMs -ge 0 -and $j.tick -ge 0 }
$null = Test-Endpoint -Name "Unauthorized Reject" -Path "/v1/health" -Headers @{"Authorization"=""} -ExpectedStatus 401
$null = Test-Endpoint -Name "OpenAPI 3.1 Spec" -Path "/openapi.json" -Headers @{"Authorization"=""} -Validator { param($j) $j.openapi.StartsWith("3.1") }
$null = Test-Endpoint -Name "Scalar Reference Root" -Path "/" -Headers @{"Authorization"=""} -Validator { param($j, $raw) $raw.Contains("scalar") }
$null = Test-Endpoint -Name "Scalar Reference /docs" -Path "/docs" -Headers @{"Authorization"=""} -Validator { param($j, $raw) $raw.Contains("scalar") }
$null = Test-Endpoint -Name "Scalar Standalone JS" -Path "/docs/scalar/standalone.js" -Headers @{"Authorization"=""} -Validator { param($j, $raw) $raw.Length -gt 1000 }
$null = Test-Endpoint -Name "ReDoc Reference UI" -Path "/redoc" -Headers @{"Authorization"=""} -Validator { param($j, $raw) $raw.Contains("redoc") }

Write-Host "`n=== 2. Client, Window & Options Tests ===" -ForegroundColor White
$null = Test-Endpoint -Name "Client State & Window" -Path "/v1/client" -Validator { param($j) $j.fps -ge 0 -and $j.window -ne $null }
$null = Test-Endpoint -Name "Live Game Options" -Path "/v1/options" -Validator { param($j) $j.fov -gt 0 -and $j.renderDistance -gt 0 }
$null = Test-Endpoint -Name "Options Patch (FOV/RenderDistance)" -Path "/v1/options" -Method "PATCH" -Body @{ fov = 85; renderDistance = 12 } -Validator { param($j) $j.fov -eq 85 -and $j.renderDistance -eq 12 }
$null = Test-Endpoint -Name "Framebuffer Screenshot" -Path "/v1/screenshot?scale=0.2" -Validator { param($j, $raw) $raw.Length -gt 50 }
$null = Test-Endpoint -Name "Screenshot Bad Scale (expected 400)" -Path "/v1/screenshot?scale=0.01" -ExpectedStatus 400 -Validator { param($j) $j.type -eq "invalid-parameter" }
$null = Test-Endpoint -Name "Built-in Registries List" -Path "/v1/registries" -Validator { param($j) $j.registries.Count -gt 30 }
$null = Test-Endpoint -Name "Query Items Registry" -Path "/v1/registries/minecraft:item?limit=5&query=diamond" -Validator { param($j) $j.entries.Count -gt 0 }
$null = Test-Endpoint -Name "Unknown Registry Query (expected 404)" -Path "/v1/registries/minecraft:unknown" -ExpectedStatus 404 -Validator { param($j) $j.type -eq "registry-not-found" }

Write-Host "`n=== 3. Reflection Engine (Milestone M3) Tests ===" -ForegroundColor White
$null = Test-Endpoint -Name "Root Handles" -Path "/v1/objects/roots" -Validator { param($j)
    $j.roots.minecraft -ne $null -and $j.roots.options -ne $null -and $j.roots.gameRenderer -ne $null
}
$null = Test-Endpoint -Name "Inspect Minecraft Root" -Path "/v1/objects/minecraft" -Validator { param($j)
    $j.'$handle' -eq "minecraft" -and $j.'$class' -ne $null -and $j.fields -ne $null
}
$null = Test-Endpoint -Name "Inspect Options Root" -Path "/v1/objects/options" -Validator { param($j)
    $j.'$handle' -eq "options" -and $j.fields -ne $null
}
$null = Test-Endpoint -Name "Inspect GameRenderer Root" -Path "/v1/objects/gameRenderer" -Validator { param($j)
    $j.'$handle' -eq "gameRenderer" -and $j.fields -ne $null
}
$null = Test-Endpoint -Name "Inspect Screen Root (TitleScreen)" -Path "/v1/objects/screen" -Validator { param($j)
    $j.'$handle' -eq "screen" -and $j.'$class'.Contains("Screen")
}
$null = Test-Endpoint -Name "Inspect Null Player Root in Menu (expected 409)" -Path "/v1/objects/player" -ExpectedStatus 409 -Validator { param($j)
    $j.type -eq "no-player"
}
$null = Test-Endpoint -Name "Inspect Null Level Root in Menu (expected 409)" -Path "/v1/objects/level" -ExpectedStatus 409 -Validator { param($j)
    $j.type -eq "no-world"
}
$null = Test-Endpoint -Name "Path Query: options.renderDistance().get()" -Path "/v1/objects/minecraft?path=options.renderDistance().get()" -Validator { param($j)
    $j.path -eq "options.renderDistance().get()" -and $j.value -ne $null
}
$null = Test-Endpoint -Name "Path Query: live client FPS" -Path "/v1/objects/minecraft?path=fps" -Validator { param($j)
    $j.path -eq "fps" -and $j.value -ne $null
}
$null = Test-Endpoint -Name "Invalid Path Query (expected 400)" -Path "/v1/objects/minecraft?path=invalid.fakeField" -ExpectedStatus 400 -Validator { param($j)
    $j.type -eq "path-evaluation-failed"
}
$fieldTest = Test-Endpoint -Name "Field Expansion: minecraft.options" -Path "/v1/objects/minecraft/fields/options?depth=1" -Validator { param($j)
    $j.field -eq "options" -and $j.value.'$handle' -ne $null
}
$null = Test-Endpoint -Name "Field Expansion: non-existent field (expected 404)" -Path "/v1/objects/minecraft/fields/nonExistent" -ExpectedStatus 404 -Validator { param($j)
    $j.type -eq "field-not-found"
}
$null = Test-Endpoint -Name "Static Class: net.minecraft.client.Minecraft" -Path "/v1/classes/net.minecraft.client.Minecraft" -Validator { param($j)
    $j.'$class' -eq "net.minecraft.client.Minecraft" -and $j.staticMethods -ne $null
}
$null = Test-Endpoint -Name "Static Class: java.lang.String" -Path "/v1/classes/java.lang.String" -Validator { param($j)
    $j.'$class' -eq "java.lang.String" -and $j.staticMethods -ne $null
}
$null = Test-Endpoint -Name "Static Class: non-existent class (expected 404)" -Path "/v1/classes/com.nonexistent.NoSuchClass" -ExpectedStatus 404 -Validator { param($j)
    $j.type -eq "class-not-found"
}
$null = Test-Endpoint -Name "Security Denylist: java.lang.Runtime (expected 403)" -Path "/v1/classes/java.lang.Runtime" -ExpectedStatus 403 -Validator { param($j)
    $j.type -eq "reflection-denied"
}
$null = Test-Endpoint -Name "Security Denylist: ClassLoader (expected 403)" -Path "/v1/classes/java.lang.ClassLoader" -ExpectedStatus 403 -Validator { param($j)
    $j.type -eq "reflection-denied"
}
$null = Test-Endpoint -Name "Cannot Release Root Handle (expected 400)" -Path "/v1/objects/minecraft" -Method "DELETE" -ExpectedStatus 400

# Test handle deletion using the handle returned from field expansion
if ($fieldTest.Json -and $fieldTest.Json.value -and $fieldTest.Json.value.'$handle') {
    $dynHandle = $fieldTest.Json.value.'$handle'
    $null = Test-Endpoint -Name "Early Release Handle ($dynHandle)" -Path "/v1/objects/$dynHandle" -Method "DELETE" -Validator { param($j)
        $j.released -eq $true
    }
    $null = Test-Endpoint -Name "Released Handle Query (expected 404)" -Path "/v1/objects/$dynHandle" -ExpectedStatus 404
}

Write-Host "`n=== 4. Input Injection & GUI Tests ===" -ForegroundColor White
$null = Test-Endpoint -Name "Key Tap Input (Escape)" -Path "/v1/input/keys" -Method "POST" -Body @{ key = "escape"; action = "tap" } -Validator { param($j) $j.action -eq "tap" }
$null = Test-Endpoint -Name "Mouse Move Input" -Path "/v1/input/mouse" -Method "POST" -Body @{ action = "move"; x = 320.0; y = 240.0 } -Validator { param($j) $j.action -eq "move" }
$null = Test-Endpoint -Name "Screen Widget Tree" -Path "/v1/screen" -Validator { param($j) $j.open -eq $true }
$null = Test-Endpoint -Name "Screen Container Slots on TitleScreen (expected 409)" -Path "/v1/screen/slots" -Method "POST" -Body @{ slot = 0; mode = "pickup"; button = 0 } -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-container" }
$null = Test-Endpoint -Name "Chat History" -Path "/v1/chat?limit=10" -Validator { param($j) $j.messages -ne $null }

Write-Host "`n=== 5. State Handling (Menu vs World) Tests ===" -ForegroundColor White
$null = Test-Endpoint -Name "Player Query in Menu (expected 409)" -Path "/v1/player" -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-player" }
$null = Test-Endpoint -Name "Player Inventory Query in Menu (expected 409)" -Path "/v1/player/inventory" -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-player" }
$null = Test-Endpoint -Name "Player Action in Menu (expected 409)" -Path "/v1/player/actions" -Method "POST" -Body @{ action = "attack" } -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-player" }
$null = Test-Endpoint -Name "Player Look in Menu (expected 409)" -Path "/v1/player/look" -Method "POST" -Body @{ yaw = 90.0; pitch = 0.0 } -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-player" }
$null = Test-Endpoint -Name "Player Move in Menu (expected 409)" -Path "/v1/player/move" -Method "POST" -Body @{ forward = $true } -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-player" }
$null = Test-Endpoint -Name "World Query in Menu (expected 409)" -Path "/v1/world" -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-world" }
$null = Test-Endpoint -Name "World Blocks Query in Menu (expected 409)" -Path "/v1/world/blocks?from=0,64,0&to=1,65,1" -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-world" }
$null = Test-Endpoint -Name "World Entities Query in Menu (expected 409)" -Path "/v1/world/entities" -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-world" }
$null = Test-Endpoint -Name "World Raycast Query in Menu (expected 409)" -Path "/v1/world/raycast" -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-world" }
$null = Test-Endpoint -Name "Chat Send in Menu (expected 409)" -Path "/v1/chat" -Method "POST" -Body @{ message = "hello" } -ExpectedStatus 409 -Validator { param($j) $j.type -eq "no-player" }

if ($ShutdownClient) {
    Write-Host "`n=== 6. Two-Step Safe Shutdown Flow ===" -ForegroundColor White
    $step1 = Test-Endpoint -Name "Quit Request (Step 1)" -Path "/v1/session/quit" -Method "POST" -ExpectedStatus 202 -Validator { param($j) $j.confirmToken -ne $null }
    if ($step1.Json -and $step1.Json.confirmToken) {
        $cToken = $step1.Json.confirmToken
        $null = Test-Endpoint -Name "Quit Confirm (Step 2)" -Path "/v1/session/quit/confirm" -Method "POST" -Body @{ confirmToken = $cToken } -ExpectedStatus 200 -Validator { param($j) $j.status -eq "shutting_down" }
    }
}

Write-Host "`n===============================================" -ForegroundColor Cyan
Write-Host "Live Test Results: $passed PASSED, $failed FAILED" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
Write-Host "===============================================`n" -ForegroundColor Cyan

if ($failed -gt 0) {
    exit 1
}
