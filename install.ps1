# Install the knot release binary for Windows.
#
#   irm https://raw.githubusercontent.com/UniSoma/knot/main/install.ps1 | iex
#
# KNOT_VERSION      release to install, e.g. 0.14.0 (default: latest)
# KNOT_INSTALL_DIR  where to put knot.exe (default: %LOCALAPPDATA%\Programs\knot)
# KNOT_RELEASE_URL  releases base URL (default: https://github.com/UniSoma/knot/releases)

function Install-Knot {
    $ErrorActionPreference = 'Stop'
    $releaseUrl = if ($env:KNOT_RELEASE_URL) { $env:KNOT_RELEASE_URL } else { 'https://github.com/UniSoma/knot/releases' }
    $installDir = if ($env:KNOT_INSTALL_DIR) { $env:KNOT_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\knot' }
    $base = if ($env:KNOT_VERSION) { "$releaseUrl/download/v$($env:KNOT_VERSION.TrimStart('v'))" } else { "$releaseUrl/latest/download" }
    $asset = 'knot-windows-amd64.zip'

    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ([System.Guid]::NewGuid())
    New-Item -ItemType Directory -Path $tmp | Out-Null
    $progress = $ProgressPreference
    try {
        # The progress bar slows Invoke-WebRequest down by an order of magnitude.
        $ProgressPreference = 'SilentlyContinue'
        Write-Host "Downloading $base/$asset"
        Invoke-WebRequest -UseBasicParsing -Uri "$base/$asset" -OutFile (Join-Path $tmp $asset)
        Invoke-WebRequest -UseBasicParsing -Uri "$base/SHA256SUMS" -OutFile (Join-Path $tmp 'SHA256SUMS')

        $line = Get-Content (Join-Path $tmp 'SHA256SUMS') | Where-Object { ($_ -split '\s+')[1] -eq $asset }
        if (-not $line) { throw "knot: $asset is not listed in SHA256SUMS" }
        $expected = ($line -split '\s+')[0].ToLower()
        $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $tmp $asset)).Hash.ToLower()
        if ($expected -ne $actual) { throw "knot: checksum mismatch for $asset (expected $expected, got $actual)" }

        Expand-Archive -Path (Join-Path $tmp $asset) -DestinationPath $tmp -Force
        New-Item -ItemType Directory -Force -Path $installDir | Out-Null
        $exe = Join-Path $installDir 'knot.exe'
        Move-Item -Force (Join-Path $tmp 'knot.exe') $exe
        Write-Host "Installed knot $(& $exe --version) to $exe"

        $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
        if (-not (($userPath -split ';') -contains $installDir)) {
            [Environment]::SetEnvironmentVariable('Path', (@($userPath, $installDir) | Where-Object { $_ }) -join ';', 'User')
            Write-Host "Added $installDir to your user PATH; open a new terminal to use knot"
        }
    }
    finally {
        $ProgressPreference = $progress
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

Install-Knot
