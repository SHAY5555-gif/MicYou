param(
    [Parameter(Mandatory)][string]$AdbPath,
    [Parameter(Mandatory)][string]$PythonPath,
    [Parameter(Mandatory)][string]$DeviceSerial,
    [Parameter(Mandatory)][string]$OutputDevice,
    [string]$PhonePackage = 'com.lanrhyme.micyou.ptt',
    [string]$InstallDirectory = "$env:LOCALAPPDATA\Programs\MicYou",
    [string]$LauncherDirectory = "$env:LOCALAPPDATA\Programs\MicYou-Singing"
)
$ErrorActionPreference = 'Stop'
foreach ($path in @($AdbPath, $PythonPath, (Join-Path $InstallDirectory 'micyou-cli.exe'))) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing dependency: $path" }
}
$configDir = Join-Path $env:APPDATA 'micyou'
$configPath = Join-Path $configDir 'singing-launcher.json'
New-Item -ItemType Directory -Path $LauncherDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $configDir -Force | Out-Null
if (Test-Path -LiteralPath $configPath) {
    Copy-Item -LiteralPath $configPath -Destination ($configPath + '.' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.backup')
}
$config = @{ adbPath = $AdbPath; pythonPath = $PythonPath; deviceSerial = $DeviceSerial; outputDevice = $OutputDevice; phonePackage = $PhonePackage; installDirectory = $InstallDirectory }
$config | ConvertTo-Json | Set-Content -LiteralPath $configPath -Encoding UTF8
# Keep the relative bridge location the same as in the repository.
$bridgeDir = Join-Path $LauncherDirectory 'tools'
$uiDir = Join-Path $bridgeDir 'windows-launcher'
New-Item -ItemType Directory -Path $uiDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'MicYou.Singing.ps1') -Destination (Join-Path $uiDir 'MicYou.Singing.ps1')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '..\windows_music_bridge.py') -Destination (Join-Path $bridgeDir 'windows_music_bridge.py')
$launcherScript = Join-Path $uiDir 'MicYou.Singing.ps1'
$source = @'
using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;
class SingingLauncher {
    [STAThread] static void Main() {
        string script = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "tools", "windows-launcher", "MicYou.Singing.ps1");
        string shell = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "WindowsPowerShell", "v1.0", "powershell.exe");
        try {
            Process.Start(new ProcessStartInfo(shell, "-NoProfile -STA -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + script + "\"") {
                UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden
            });
        } catch (Exception ex) { MessageBox.Show(ex.Message, "MicYou Singing"); }
    }
}
'@
$sourcePath = Join-Path $LauncherDirectory 'SingingLauncher.cs'
[IO.File]::WriteAllText($sourcePath, $source)
$compiler = Join-Path $env:SystemRoot 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$exePath = Join-Path $LauncherDirectory 'MicYou Singing.exe'
& $compiler /nologo /target:winexe /r:System.Windows.Forms.dll "/out:$exePath" $sourcePath
if ($LASTEXITCODE -ne 0) { throw 'Could not compile the native launcher.' }
$shell = New-Object -ComObject WScript.Shell
foreach ($directory in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
    $shortcut = $shell.CreateShortcut((Join-Path $directory 'MicYou Singing.lnk'))
    $shortcut.TargetPath = $exePath
    $shortcut.WorkingDirectory = $LauncherDirectory
    $shortcut.IconLocation = (Join-Path $InstallDirectory 'micyou.exe') + ',0'
    $shortcut.Description = 'Connect the phone microphone to the selected PC speaker'
    $shortcut.Save()
}
Write-Output "Installed: $exePath"
