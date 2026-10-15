<#
.SYNOPSIS
  Selects .env.dev or .env.prod as the active .env copy. Does not run Docker.
.EXAMPLE
  .\ops\env.ps1 status
  .\ops\env.ps1 use dev
  .\ops\env.ps1 use prod
#>
param(
    [Parameter(Position = 0)][ValidateSet('status', 'use')][string]$Command = 'status',
    [Parameter(Position = 1)][ValidateSet('dev', 'prod')][string]$ProfileName,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$activePath = Join-Path $root '.env'
$markerPrefix = '# RESTOFY-ACTIVE-PROFILE='
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-Text([string]$Path) {
    return [System.IO.File]::ReadAllText($Path, $utf8)
}

function Read-Active {
    if (-not (Test-Path -LiteralPath $activePath -PathType Leaf)) { return $null }
    $text = Read-Text $activePath
    $newline = $text.IndexOf("`n")
    $first = if ($newline -ge 0) { $text.Substring(0, $newline).TrimEnd("`r") } else { $text }
    if ($first.StartsWith($markerPrefix)) {
        $profile = $first.Substring($markerPrefix.Length).Trim()
        if ($profile -in @('dev', 'prod')) {
            $body = if ($newline -ge 0) { $text.Substring($newline + 1) } else { '' }
            return @{ Profile = $profile; Body = $body }
        }
    }
    return @{ Profile = $null; Body = $text }
}

function Same-Text([string]$a, [string]$b) {
    return ($a -replace "`r`n", "`n") -ceq ($b -replace "`r`n", "`n")
}

$active = Read-Active
if ($Command -eq 'status') {
    if (-not $active) {
        Write-Host 'No .env yet. Run: .\ops\env.cmd use dev' -ForegroundColor Yellow
        return
    }
    if (-not $active.Profile) {
        Write-Host '.env has no profile marker (not written by this script).' -ForegroundColor Yellow
        return
    }
    $file = Join-Path $root ".env.$($active.Profile)"
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        Write-Host "Active profile: $($active.Profile) (profile file is missing)" -ForegroundColor Yellow
    } elseif (Same-Text $active.Body (Read-Text $file)) {
        Write-Host "Active profile: $($active.Profile) (.env matches .env.$($active.Profile))" -ForegroundColor Green
    } else {
        Write-Host "Active profile: $($active.Profile) (.env differs from .env.$($active.Profile))" -ForegroundColor Yellow
    }
    return
}

if (-not $ProfileName) { throw 'Name a profile: .\ops\env.cmd use dev|prod' }
$source = Join-Path $root ".env.$ProfileName"
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw ".env.$ProfileName not found." }
$content = Read-Text $source

if ($active) {
    $recorded = if ($active.Profile) { Join-Path $root ".env.$($active.Profile)" } else { $null }
    $inSync = $recorded -and (Test-Path -LiteralPath $recorded -PathType Leaf) -and (Same-Text $active.Body (Read-Text $recorded))
    if (-not $inSync -and -not $Force) {
        throw '.env differs from its profile file. Move edits into .env.dev / .env.prod, or use -Force to back up .env to .env.bak before switching.'
    }
    if (-not $inSync) {
        Copy-Item -LiteralPath $activePath -Destination (Join-Path $root '.env.bak') -Force
        Write-Host 'Backed up the current .env to .env.bak' -ForegroundColor Yellow
    }
}

$eol = if ($content.Contains("`r`n")) { "`r`n" } else { "`n" }
[System.IO.File]::WriteAllText($activePath, "$markerPrefix$ProfileName$eol$content", $utf8)
Write-Host "Active profile: $ProfileName (.env written from .env.$ProfileName)" -ForegroundColor Green
Write-Host 'Restart running applications to apply the change: docker compose up --build -d' -ForegroundColor Cyan
