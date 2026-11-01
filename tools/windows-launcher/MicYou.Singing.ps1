param(
    [ValidateSet('App', 'Start', 'Stop', 'Status', 'Render')][string]$Action = 'App',
    [string]$ConfigPath = "$env:APPDATA\micyou\singing-launcher.json",
    [string]$RenderPath
)

$ErrorActionPreference = 'Stop'
$script:config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
$script:dataDir = Split-Path -Parent $ConfigPath
$script:statePath = Join-Path $script:dataDir 'singing-state.json'
$script:cliPath = Join-Path $script:config.installDirectory 'micyou-cli.exe'

function Invoke-Adb([string[]]$Arguments) {
    # Windows PowerShell turns benign native stderr warnings into error records.
    $ErrorActionPreference = 'Continue'
    $result = & $script:config.adbPath -s $script:config.deviceSerial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Phone command failed: $($result -join ' ')" }
    return ($result -join "`n")
}

function Get-PhoneUi {
    $raw = Invoke-Adb @('exec-out', 'uiautomator', 'dump', '/dev/tty')
    $start = $raw.IndexOf('<?xml')
    $end = $raw.IndexOf('</hierarchy>')
    if ($start -lt 0 -or $end -lt $start) { throw 'Unlock the phone and open MicYou.' }
    return [xml]$raw.Substring($start, $end + 12 - $start)
}

function Press-PhoneControl([xml]$Document, [string]$Name) {
    $node = @($Document.SelectNodes('//node')) | Where-Object {
        $_.text -eq $Name -or $_.'content-desc' -eq $Name
    } | Select-Object -First 1
    if (-not $node) { return $false }
    while ($node -and ($node.clickable -ne 'true' -or $node.enabled -ne 'true')) {
        $node = $node.ParentNode
    }
    if (-not $node -or $node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') { return $false }
    $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
    $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
    Invoke-Adb @('shell', 'input', 'tap', "$x", "$y") | Out-Null
    return $true
}

function Get-Outputs {
    @(& $script:cliPath devices) | ForEach-Object {
        if ($_ -match '^\s+\d+\. (.+)$') { $Matches[1] }
    }
}

function Test-LocalPort([int]$Port) {
    return @([Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners() |
        Where-Object { $_.Port -eq $Port -and $_.Address.ToString() -in @('127.0.0.1', '0.0.0.0', '::1', '::') }).Count -gt 0
}

function Stop-VerifiedProcess([int]$ProcessId, [string]$ExpectedPath, [long]$ExpectedStartTicks = 0) {
    if ($ProcessId -le 0) { return }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId"
    if (-not $process) { return }
    if ($process.ExecutablePath -ne $ExpectedPath) { throw 'Process identity changed; refusing to stop it.' }
    if ($ExpectedStartTicks -and [Math]::Abs($process.CreationDate.ToUniversalTime().Ticks - $ExpectedStartTicks) -gt [TimeSpan]::TicksPerMillisecond) {
        throw 'Process start time changed; refusing to stop a reused PID.'
    }
    Stop-Process -Id $ProcessId
    # Let Windows finish closing handles before MicYou checks its mode lock.
    for ($i = 0; $i -lt 30; $i++) {
        if (-not (Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId")) { break }
        Start-Sleep -Milliseconds 100
    }
    Start-Sleep -Milliseconds 250
}

function Stop-Singing {
    if (-not (Test-Path -LiteralPath $script:statePath)) { return }
    $state = Get-Content -LiteralPath $script:statePath -Raw | ConvertFrom-Json
    $wasRunning = [bool](Get-CimInstance Win32_Process -Filter "ProcessId = $($state.receiverPid)")
    Stop-VerifiedProcess $state.receiverPid $script:cliPath $state.receiverStartTicks
    if ($wasRunning) {
        try { Invoke-Adb @('shell', 'am', 'force-stop', $script:config.phonePackage) | Out-Null }
        catch { Write-Warning 'Receiver stopped. Phone unavailable for closing the microphone app.' }
    }
    if ($state.bridgePid) { Stop-VerifiedProcess $state.bridgePid $script:config.pythonPath $state.bridgeStartTicks }
    # Keep the state and logs as recoverable evidence; a stopped PID is harmless.
}

function Start-Singing {
    $serials = @(& $script:config.adbPath devices) -join "`n"
    $escaped = [regex]::Escape($script:config.deviceSerial)
    if ($serials -notmatch "(?m)^$escaped\s+device\s*$") { throw 'Connect and unlock the configured phone with its USB data cable.' }
    $outputs = @(Get-Outputs)
    if ($script:config.outputDevice -notin $outputs) { throw 'The selected speaker is disconnected. Select an available output.' }

    $lockPath = Join-Path $script:dataDir 'mode.lock'
    if (Test-Path -LiteralPath $lockPath) {
        $lock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
        $running = Get-CimInstance Win32_Process -Filter "ProcessId = $($lock.pid)"
        if ($running) {
            if ($lock.mode -ne 'cli' -or $running.ExecutablePath -ne $script:cliPath) {
                throw 'Close the other MicYou window before connecting here.'
            }
            Stop-VerifiedProcess $lock.pid $script:cliPath
        }
        # Windows may retain a terminated process handle after it disappears
        # from the process table. Old MicYou builds mistake that for a live PID.
        $currentLock = Get-Content -LiteralPath $lockPath -Raw | ConvertFrom-Json
        if ($currentLock.pid -eq $lock.pid -and -not (Get-CimInstance Win32_Process -Filter "ProcessId = $($lock.pid)")) {
            Copy-Item -LiteralPath $lockPath -Destination ($lockPath + '.' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.backup')
            @{ mode = 'cli'; pid = 0; started_at = 0 } | ConvertTo-Json | Set-Content -LiteralPath $lockPath -Encoding ASCII
        }
    }

    Invoke-Adb @('reverse', 'tcp:8554', 'tcp:8554') | Out-Null
    Invoke-Adb @('reverse', 'tcp:8556', 'tcp:8556') | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss-fff'
    $env:PATH = (Split-Path -Parent $script:config.adbPath) + ';' + $env:PATH
    $receiverLog = Join-Path $script:dataDir "singing-$stamp.log"
    $receiver = Start-Process -FilePath $script:cliPath -ArgumentList @(
        'serve', '--mode', 'usb', '--device', ('"' + $script:config.outputDevice + '"'), '--bind', '127.0.0.1'
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $receiverLog -RedirectStandardError (Join-Path $script:dataDir "singing-$stamp-errors.log")
    $bridgePid = 0
    $bridgeStartTicks = 0
    if (Test-Path -LiteralPath $script:statePath) {
        $previous = Get-Content -LiteralPath $script:statePath -Raw | ConvertFrom-Json
        if ($previous.bridgePid) {
            $bridge = Get-CimInstance Win32_Process -Filter "ProcessId = $($previous.bridgePid)"
            if ($bridge -and $bridge.ExecutablePath -eq $script:config.pythonPath) {
                $bridgePid = $previous.bridgePid
                $bridgeStartTicks = $bridge.CreationDate.ToUniversalTime().Ticks
            }
        }
    }
    if (-not (Test-LocalPort 8556)) {
        $bridge = Start-Process -FilePath $script:config.pythonPath -ArgumentList ('"' + (Join-Path $PSScriptRoot '..\windows_music_bridge.py') + '"') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $script:dataDir "music-$stamp.log") -RedirectStandardError (Join-Path $script:dataDir "music-$stamp-errors.log")
        $bridgePid = $bridge.Id
        $bridgeStartTicks = $bridge.StartTime.ToUniversalTime().Ticks
    }
    @{ receiverPid = $receiver.Id; receiverStartTicks = $receiver.StartTime.ToUniversalTime().Ticks; bridgePid = $bridgePid; bridgeStartTicks = $bridgeStartTicks; receiverLog = $receiverLog } | ConvertTo-Json | Set-Content -LiteralPath $script:statePath -Encoding UTF8
    try {
        for ($i = 0; $i -lt 40; $i++) {
            if ($receiver.HasExited) { throw 'The audio receiver could not start. Check the retained error log.' }
            if (Test-LocalPort 8554) { break }
            Start-Sleep -Milliseconds 100
        }
        if (-not (Test-LocalPort 8554)) { throw 'The audio receiver did not become ready.' }
        Invoke-Adb @('shell', 'am', 'start', '--activity-single-top', '-n', ($script:config.phonePackage + '/com.lanrhyme.micyou.MainActivity')) | Out-Null
        $ui = Get-PhoneUi
        # Reconnect explicitly rather than trusting the phone's old streaming screen.
        if (Press-PhoneControl $ui 'Disconnect') { $ui = Get-PhoneUi }
        if (Press-PhoneControl $ui 'USB') { $ui = Get-PhoneUi }
        [void](Press-PhoneControl $ui 'Connect microphone')
        for ($attempt = 0; $attempt -lt 6; $attempt++) {
            $ui = Get-PhoneUi
            if (Press-PhoneControl $ui 'Turn microphone on') { $ui = Get-PhoneUi; break }
            Start-Sleep -Milliseconds 250
        }
        $texts = @($ui.SelectNodes('//node') | ForEach-Object { $_.text; $_.'content-desc' })
        if ('Disconnect' -notin $texts -or 'Mute microphone' -notin $texts) {
            throw 'Receiver ready. Unlock the phone, choose USB, connect, and turn on the microphone.'
        }
        if ((Get-Content -LiteralPath $receiverLog -Raw) -notmatch '\[mic\] connected:') {
            throw 'The phone screen changed, but the receiver has not confirmed the connection.'
        }
        return 'Connected. Microphone on. Mixer controls are on the phone.'
    } catch {
        # A failed start must not leave an unexpected live microphone receiver.
        Stop-Singing
        throw
    }
}

if ($Action -eq 'Start') { Start-Singing; exit }
if ($Action -eq 'Stop') { Stop-Singing; exit }
if ($Action -eq 'Status') {
    [pscustomobject]@{ receiverListening = (Test-LocalPort 8554); musicBridgeListening = (Test-LocalPort 8556); output = $script:config.outputDevice } | ConvertTo-Json -Compress
    exit
}

$created = $false
$appMutex = New-Object Threading.Mutex($true, 'Local\MicYouSingingWindow', [ref]$created)
if (-not $created) { $appMutex.Dispose(); exit }

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$form = New-Object Windows.Forms.Form
$form.Text = 'MicYou Singing'
$form.ClientSize = New-Object Drawing.Size(590, 350)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.BackColor = [Drawing.Color]::FromArgb(23, 27, 33)
$form.ForeColor = [Drawing.Color]::White
$form.Font = New-Object Drawing.Font('Segoe UI', 11)
$form.Icon = [Drawing.Icon]::ExtractAssociatedIcon((Join-Path $script:config.installDirectory 'micyou.exe'))

function Add-Label([string]$Text, [int]$Y, [int]$Height) {
    $label = New-Object Windows.Forms.Label
    $label.Text = $Text; $label.Location = New-Object Drawing.Point(26, $Y)
    $label.Size = New-Object Drawing.Size(538, $Height)
    $label.BackColor = $form.BackColor; $label.ForeColor = $form.ForeColor
    $form.Controls.Add($label)
    return $label
}
$title = Add-Label 'MicYou Singing' 22 42
$title.Font = New-Object Drawing.Font('Segoe UI', 23, [Drawing.FontStyle]::Bold)
$subtitle = Add-Label 'Phone microphone. PC speakers. One connection.' 69 28
$subtitle.ForeColor = [Drawing.Color]::Silver
$outputLabel = Add-Label 'Speaker output' 113 25
$outputs = New-Object Windows.Forms.ComboBox
$outputs.Location = New-Object Drawing.Point(26, 143)
$outputs.Size = New-Object Drawing.Size(538, 32)
$outputs.DropDownStyle = 'DropDownList'
$outputs.DrawMode = 'OwnerDrawFixed'
$outputs.ItemHeight = 25
$outputs.Add_DrawItem({
    param($sender, $eventArgs)
    $eventArgs.DrawBackground()
    $index = $eventArgs.Index
    if ($index -lt 0) { $index = $sender.SelectedIndex }
    if ($index -ge 0) {
        [Windows.Forms.TextRenderer]::DrawText($eventArgs.Graphics, [string]$sender.Items[$index], $sender.Font, $eventArgs.Bounds, [Drawing.Color]::Black, [Windows.Forms.TextFormatFlags]::Left -bor [Windows.Forms.TextFormatFlags]::VerticalCenter)
    }
    $eventArgs.DrawFocusRectangle()
})
foreach ($name in @(Get-Outputs)) { [void]$outputs.Items.Add($name) }
$outputs.SelectedIndex = $outputs.FindStringExact([string]$script:config.outputDevice)
$form.Controls.Add($outputs)
$status = Add-Label 'Ready to connect the phone over USB.' 197 51
$status.ForeColor = [Drawing.Color]::FromArgb(111, 223, 194)

function Add-Button([string]$Text, [int]$X, [int]$Width) {
    $button = New-Object Windows.Forms.Button
    $button.Text = $Text; $button.Location = New-Object Drawing.Point($X, 266)
    $button.Size = New-Object Drawing.Size($Width, 47)
    $button.FlatStyle = 'Flat'; $button.BackColor = [Drawing.Color]::FromArgb(42, 50, 60)
    $button.ForeColor = [Drawing.Color]::White
    $form.Controls.Add($button)
    return $button
}
$connect = Add-Button 'Connect' 26 164
$stop = Add-Button 'Stop' 204 164
$mixer = Add-Button 'Phone mixer' 382 182
$connect.Add_Click({
    $connect.Enabled = $false; $status.Text = 'Connecting...'; $form.Refresh()
    try {
        if (-not $outputs.SelectedItem) { throw 'Choose an available speaker.' }
        $script:config.outputDevice = [string]$outputs.SelectedItem
        $script:config | ConvertTo-Json | Set-Content -LiteralPath $ConfigPath -Encoding UTF8
        $status.Text = Start-Singing
    } catch { $status.Text = $_.Exception.Message }
    finally { $connect.Enabled = $true }
})
$stop.Add_Click({ try { Stop-Singing; $status.Text = 'Stopped.' } catch { $status.Text = $_.Exception.Message } })
$mixer.Add_Click({
    try { Invoke-Adb @('shell', 'am', 'start', '--activity-single-top', '-n', ($script:config.phonePackage + '/com.lanrhyme.micyou.MainActivity')) | Out-Null }
    catch { $status.Text = $_.Exception.Message }
})
if ($Action -eq 'Render') {
    if (-not $RenderPath) { throw 'RenderPath is required.' }
    $forceCreate = [Windows.Forms.Control].GetMethod('CreateControl', [Reflection.BindingFlags]'Instance,NonPublic', $null, [type[]]@([bool]), $null)
    [void]$forceCreate.Invoke($form, @($true))
    foreach ($control in $form.Controls) { [void]$forceCreate.Invoke($control, @($true)) }
    $form.PerformLayout()
    $bitmap = New-Object Drawing.Bitmap($form.ClientSize.Width, $form.ClientSize.Height)
    $graphics = [Drawing.Graphics]::FromImage($bitmap)
    $graphics.Clear($form.BackColor)
    $graphics.Dispose()
    # A hidden parent reports its children as invisible. Detach each child only
    # for offscreen painting; no window is shown or activated.
    foreach ($control in @($form.Controls)) {
        $bounds = $control.Bounds
        $form.Controls.Remove($control)
        $control.DrawToBitmap($bitmap, $bounds)
        $form.Controls.Add($control)
    }
    $bitmap.Save($RenderPath)
    [pscustomobject]@{ controlCount = $form.Controls.Count; outputCount = $outputs.Items.Count; selectedOutput = $outputs.Text } | ConvertTo-Json -Compress
    $bitmap.Dispose(); $form.Dispose(); $appMutex.Dispose(); exit
}
$form.Add_FormClosed({ Stop-Singing })
$form.Add_Shown({ $connect.PerformClick() })
[void]$form.ShowDialog()
$appMutex.Dispose()
