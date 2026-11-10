param(
    [string]$InstallRoot = "$env:LOCALAPPDATA\Programs\MicYou-Studio"
)
$ErrorActionPreference = 'Stop'
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$versionDirectory = Join-Path $InstallRoot ('build-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
& dotnet publish (Join-Path $PSScriptRoot 'MicYou.Studio.csproj') -c Release --no-restore --self-contained false -o $versionDirectory
if ($LASTEXITCODE -ne 0) { throw 'Desktop app build failed' }
$executable = Join-Path $versionDirectory 'MicYou.Studio.exe'
if (-not (Test-Path -LiteralPath $executable)) { throw 'The desktop executable was not created' }
$shell = New-Object -ComObject WScript.Shell
foreach ($directory in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
    $link = Join-Path $directory 'MicYou Singing.lnk'
    if (Test-Path -LiteralPath $link) {
        Copy-Item -LiteralPath $link -Destination (Join-Path $versionDirectory ([IO.Path]::GetFileName($directory) + '-previous.lnk'))
    }
    $shortcut = $shell.CreateShortcut($link)
    $shortcut.TargetPath = $executable
    $shortcut.WorkingDirectory = $versionDirectory
    $shortcut.Description = 'Live microphone mixer and lossless WAV recording'
    $shortcut.Save()
}
[pscustomobject]@{ executable = $executable; directory = $versionDirectory } | ConvertTo-Json -Compress
