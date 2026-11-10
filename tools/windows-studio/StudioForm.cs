using System.Diagnostics;
using System.ComponentModel;
using System.Drawing.Drawing2D;
using System.Reflection;
using NAudio.CoreAudioApi;

namespace MicYou.Studio;

public sealed class StudioForm : Form
{
    private static readonly Color Canvas = Color.FromArgb(18, 21, 30);
    private static readonly Color Surface = Color.FromArgb(31, 36, 49);
    private static readonly Color Accent = Color.FromArgb(137, 153, 255);
    private static readonly Color Coral = Color.FromArgb(255, 135, 132);
    private readonly StudioSettings settings;
    private readonly ComboBox inputs = DeviceCombo();
    private readonly ComboBox outputs = DeviceCombo();
    private readonly ComboBox processing = DeviceCombo();
    private readonly Label status;
    private readonly Label format;
    private readonly Label recordingStatus;
    private readonly Button start;
    private readonly Button record;
    private readonly Button stop;
    private readonly Button audacity;
    private readonly Button refresh;
    private readonly LevelMeter inputMeter = new();
    private readonly LevelMeter outputMeter = new();
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 80 };
    private LiveAudioSession? session;
    private string? lastRecording;
    private bool busy;
    private bool closing;
    private bool allowClose;
    private bool refreshing;
    private int failurePosted;
    private readonly bool autoStart;
    private readonly Dictionary<Control, Rectangle> designBounds = new();

    public StudioForm(bool autoStart)
    {
        this.autoStart = autoStart;
        settings = StudioSettings.Load();
        Text = "MicYou Singing";
        ClientSize = new Size(940, 690);
        MinimumSize = new Size(940, 729);
        MaximumSize = new Size(940, 729);
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = Canvas;
        ForeColor = Color.FromArgb(237, 240, 251);
        Font = new Font("Segoe UI", 11);
        AutoScaleMode = AutoScaleMode.None;

        TextLabel("MicYou", 30, 20, 250, 52, 30, false).Font = new Font("Segoe UI", 30, FontStyle.Bold);
        TextLabel("אולפן שירה חי", 570, 32, 340, 38, 19);
        TextLabel("מיקרופון במחשב • שמיעה חיה • הקלטת WAV", 390, 77, 520, 28, 11).ForeColor = Color.Silver;

        TextLabel("מיקרופון", 30, 121, 425, 24);
        TextLabel("רמקול / אוזניות", 485, 121, 425, 24);
        Place(inputs, 30, 151, 425, 34);
        Place(outputs, 485, 151, 425, 34);
        TextLabel("מצב שמע", 692, 208, 218, 26);
        processing.Items.AddRange(["נקי • שירה ומוזיקה", "ביטול הד • לפי תמיכת המיקרופון"]);
        processing.SelectedIndex = 0;
        Place(processing, 370, 205, 305, 34);
        refresh = Button("רענן התקנים", 30, 202, 154, 38);
        refresh.Click += (_, _) => RefreshDevices();
        var phone = Button("מיקרופון הטלפון", 198, 202, 158, 38);
        phone.Click += (_, _) => OpenProgram(Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "MicYou-Singing", "MicYou Singing.exe"));

        status = TextLabel("בחר מיקרופון ורמקול כדי להתחיל", 30, 260, 880, 29, 12);
        status.ForeColor = Accent;
        format = TextLabel("WAV ללא דחיסה נשמר ישירות מהקלט", 30, 291, 880, 23, 10);
        format.ForeColor = Color.Silver;
        TextLabel("קלט", 799, 334, 111, 23, 10);
        TextLabel("שמיעה", 340, 334, 111, 23, 10);
        Place(inputMeter, 485, 338, 307, 13);
        Place(outputMeter, 30, 338, 301, 13);

        AddFader("קול לשמיעה", 30, 365, 0, 300, (int)(settings.MicrophoneGain * 100), "%", value =>
        {
            settings.MicrophoneGain = value / 100f;
            if (session?.Monitor is { } monitor) Volatile.Write(ref monitor.Gain, settings.MicrophoneGain);
        });
        AddFader("עוצמת שמיעה", 255, 365, 0, 100, (int)(settings.ListeningVolume * 100), "%", value =>
        {
            settings.ListeningVolume = value / 100f;
            if (session?.Monitor is { } monitor) Volatile.Write(ref monitor.Volume, settings.ListeningVolume);
        });
        AddFader("אקו", 480, 365, 0, 40, (int)(settings.EchoLevel * 100), "%", value =>
        {
            settings.EchoLevel = value / 100f;
            if (session?.Monitor is { } monitor) Volatile.Write(ref monitor.Echo, settings.EchoLevel);
        });
        AddFader("זמן האקו", 705, 365, 50, 450, settings.EchoDelayMs, "ms", value =>
        {
            settings.EchoDelayMs = value;
            if (session?.Monitor is { } monitor) Volatile.Write(ref monitor.EchoDelayMs, value);
        });

        recordingStatus = TextLabel("הקלטה שומרת את המיקרופון המקורי", 230, 566, 680, 25, 10);
        var folder = Button("תיקיית הקלטות", 30, 560, 180, 38);
        folder.Click += (_, _) => OpenProgram(settings.RecordingFolder);
        start = Button("הפעל שמיעה", 30, 610, 210, 52, Accent);
        record = Button("הקלט", 252, 610, 210, 52, Coral);
        stop = Button("עצור", 474, 610, 210, 52);
        audacity = Button("פתח ב־Audacity", 696, 610, 214, 52);
        start.Click += async (_, _) => await RunSafe(async () =>
        {
            if (session == null) await StartAsync();
            else if (session.Monitor is { } monitor)
            {
                monitor.Listening = !monitor.Listening;
                status.Text = monitor.Listening ? "שמיעה חיה פועלת" : "שמיעה מושתקת";
            }
        });
        record.Click += async (_, _) => await RunSafe(async () =>
        {
            if (session == null) await StartAsync();
            if (session!.Recording == null)
            {
                session.StartRecording(settings.RecordingFolder);
                lastRecording = session.LastRecording;
            }
            else await FinishRecordingAsync();
        });
        stop.Click += async (_, _) => await RunSafe(StopAsync);
        audacity.Click += (_, _) =>
        {
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "Programs", "Audacity", "bin", "Audacity4.exe");
            OpenProgram(path, lastRecording != null && session?.Recording == null ? lastRecording : null);
        };
        inputs.SelectionChangeCommitted += async (_, _) => await ChangeDeviceAsync();
        outputs.SelectionChangeCommitted += async (_, _) => await ChangeDeviceAsync();
        processing.SelectionChangeCommitted += async (_, _) => await ChangeDeviceAsync();
        timer.Tick += (_, _) => UpdateMeters();
        Shown += async (_, _) =>
        {
            timer.Start();
            if (autoStart && inputs.SelectedItem != null && outputs.SelectedItem != null) await RunSafe(StartAsync);
        };
        FormClosing += async (_, args) =>
        {
            if (allowClose) return;
            args.Cancel = true;
            if (closing) return;
            closing = true;
            timer.Stop();
            while (busy) await Task.Delay(30);
            await RunSafe(StopAsync);
            allowClose = true;
            Close();
        };
        FormClosed += (_, _) => timer.Dispose();
        RefreshDevices();
        UpdateButtons();
    }

    private static ComboBox DeviceCombo()
    {
        var combo = new ComboBox
        {
            DropDownStyle = ComboBoxStyle.DropDownList,
            Font = new Font("Segoe UI", 10),
            DrawMode = DrawMode.OwnerDrawFixed,
            ItemHeight = 24,
            BackColor = Color.FromArgb(230, 233, 244),
            ForeColor = Color.FromArgb(20, 23, 33)
        };
        combo.DrawItem += (_, args) =>
        {
            args.DrawBackground();
            var index = args.Index < 0 ? combo.SelectedIndex : args.Index;
            if (index >= 0) TextRenderer.DrawText(args.Graphics, combo.Items[index]?.ToString() ?? "",
                combo.Font, args.Bounds, combo.ForeColor, TextFormatFlags.Left | TextFormatFlags.VerticalCenter);
        };
        return combo;
    }

    private void Place(Control control, int x, int y, int width, int height)
    {
        control.Bounds = new Rectangle(x, y, width, height);
        designBounds[control] = control.Bounds;
        Controls.Add(control);
    }

    protected override void OnLoad(EventArgs e)
    {
        ApplyDisplayScale();
        base.OnLoad(e);
    }

    protected override void OnDpiChanged(DpiChangedEventArgs e)
    {
        base.OnDpiChanged(e);
        ApplyDisplayScale();
    }

    private void ApplyDisplayScale()
    {
        var scale = DeviceDpi / 96f;
        SuspendLayout();
        MinimumSize = Size.Empty;
        MaximumSize = Size.Empty;
        ClientSize = new Size((int)(940 * scale), (int)(690 * scale));
        foreach (var (control, bounds) in designBounds)
        {
            if (control is ComboBox combo) combo.ItemHeight = (int)(24 * scale);
            control.Bounds = new Rectangle((int)(bounds.X * scale), (int)(bounds.Y * scale),
                (int)(bounds.Width * scale), (int)(bounds.Height * scale));
        }
        MinimumSize = Size;
        MaximumSize = Size;
        ResumeLayout();
    }

    private Label TextLabel(string text, int x, int y, int width, int height, int size = 11, bool rtl = true)
    {
        var label = new Label
        {
            Text = text,
            Font = new Font("Segoe UI", size),
            BackColor = Canvas,
            ForeColor = ForeColor,
            TextAlign = rtl ? ContentAlignment.MiddleRight : ContentAlignment.MiddleLeft
        };
        Place(label, x, y, width, height);
        return label;
    }

    private Button Button(string text, int x, int y, int width, int height, Color? color = null)
    {
        var button = new Button
        {
            Text = text,
            FlatStyle = FlatStyle.Flat,
            BackColor = color ?? Surface,
            ForeColor = color.HasValue ? Canvas : ForeColor,
            Cursor = Cursors.Hand,
            Font = new Font("Segoe UI", 11, FontStyle.Bold)
        };
        button.FlatAppearance.BorderSize = 0;
        Place(button, x, y, width, height);
        return button;
    }

    private void AddFader(string name, int x, int y, int minimum, int maximum, int value, string unit, Action<int> changed)
    {
        var fader = new MixerFader(name, minimum, maximum, value, unit) { BackColor = Surface };
        Place(fader, x, y, 205, 180);
        fader.ValueChanged += (_, _) => { changed(fader.Value); settings.Save(); };
    }

    private void RefreshDevices()
    {
        if (session?.Recording != null) return;
        refreshing = true;
        try
        {
            var source = StudioSettings.Endpoints(DataFlow.Capture);
            var target = StudioSettings.Endpoints(DataFlow.Render);
            inputs.Items.Clear();
            outputs.Items.Clear();
            inputs.Items.AddRange(source.Cast<object>().ToArray());
            outputs.Items.AddRange(target.Cast<object>().ToArray());
            inputs.SelectedItem = source.FirstOrDefault(x => x.Id == settings.InputId)
                ?? source.FirstOrDefault(x => x.Name.Contains("Wireless Mic Rx", StringComparison.OrdinalIgnoreCase));
            outputs.SelectedItem = target.FirstOrDefault(x => x.Id == settings.OutputId)
                ?? target.FirstOrDefault(x => x.Name.Contains("EP-HDMI-RX", StringComparison.OrdinalIgnoreCase));
            if (inputs.SelectedItem == null) status.Text = "בחר מיקרופון מחובר מהרשימה";
        }
        catch (Exception error) { status.Text = error.Message; }
        finally { refreshing = false; }
    }

    private async Task ChangeDeviceAsync()
    {
        if (refreshing || busy) return;
        await RunSafe(async () =>
        {
            if (session != null) { await StopAsync(); await StartAsync(); }
        });
    }

    private async Task StartAsync()
    {
        if (inputs.SelectedItem is not AudioEndpoint source || outputs.SelectedItem is not AudioEndpoint target)
            throw new InvalidOperationException("בחר מיקרופון ורמקול מחוברים");
        settings.InputId = source.Id;
        settings.OutputId = target.Id;
        settings.Save();
        var next = new LiveAudioSession();
        failurePosted = 0;
        next.Failed += message =>
        {
            if (Interlocked.Exchange(ref failurePosted, 1) != 0 || IsDisposed || !IsHandleCreated) return;
            BeginInvoke(async () =>
            {
                while (busy && !closing) await Task.Delay(30);
                if (!closing) await RunSafe(async () => { await StopAsync(); status.Text = message; });
            });
        };
        var cancelEcho = processing.SelectedIndex == 1;
        try { await Task.Run(() => next.Start(source.Id, target.Id, cancelEcho, settings)); }
        catch { await next.DisposeAsync(); throw; }
        session = next;
        status.Text = $"שמיעה חיה • {target.Name}";
        var audio = next.CaptureFormat!;
        var sampleType = next.CaptureIsFloat ? "float" : "PCM";
        format.Text = $"{audio.SampleRate:N0} Hz • {audio.BitsPerSample}-bit {sampleType} • {audio.Channels} ערוצים • WAV ללא דחיסה";
    }

    private async Task FinishRecordingAsync()
    {
        if (session == null) return;
        await session.StopRecordingAsync();
        recordingStatus.Text = $"נשמר {Path.GetFileName(lastRecording)}";
    }

    private async Task StopAsync()
    {
        var previous = session;
        session = null;
        if (previous != null)
        {
            lastRecording = previous.LastRecording ?? lastRecording;
            await Task.Run(async () => await previous.DisposeAsync());
        }
        status.Text = "המיקרופון עצור";
        if (lastRecording != null) recordingStatus.Text = $"נשמר {Path.GetFileName(lastRecording)}";
    }

    private async Task RunSafe(Func<Task> action)
    {
        if (busy) return;
        busy = true;
        UpdateButtons();
        try { await action(); }
        catch (Exception error) { status.Text = $"לא ניתן להשלים את הפעולה • {error.Message}"; }
        finally { busy = false; UpdateButtons(); }
    }

    private void UpdateButtons()
    {
        start.Enabled = record.Enabled = stop.Enabled = !busy;
        refresh.Enabled = !busy && session == null;
        inputs.Enabled = outputs.Enabled = processing.Enabled = !busy && session?.Recording == null;
        audacity.Enabled = !busy && session?.Recording == null;
        start.Text = session == null ? "הפעל שמיעה" : session.Monitor?.Listening == true ? "השתק שמיעה" : "חדש שמיעה";
        record.Text = session?.Recording == null ? "הקלט" : "סיים הקלטה";
    }

    private void UpdateMeters()
    {
        inputMeter.Level = session == null ? 0 : Interlocked.Exchange(ref session.InputPeak, 0);
        outputMeter.Level = session?.Monitor is { } monitor ? Interlocked.Exchange(ref monitor.OutputPeak, 0) : 0;
        if (session?.Recording is { } recording)
        {
            recordingStatus.ForeColor = Coral;
            recordingStatus.Text = recording.Error ?? $"מקליט WAV • {(DateTime.Now - recording.StartedAt):mm\\:ss}";
        }
        else recordingStatus.ForeColor = Color.Silver;
    }

    private void OpenProgram(string path, string? argument = null)
    {
        try
        {
            if (path == settings.RecordingFolder) Directory.CreateDirectory(path);
            var info = new ProcessStartInfo(path) { UseShellExecute = true };
            if (argument != null) info.ArgumentList.Add(argument);
            Process.Start(info);
        }
        catch (Exception error) { status.Text = error.Message; }
    }

    public void Render(string path)
    {
        var create = typeof(Control).GetMethod("CreateControl", BindingFlags.Instance | BindingFlags.NonPublic,
            null, [typeof(bool)], null)!;
        create.Invoke(this, [true]);
        foreach (Control control in Controls) create.Invoke(control, [true]);
        using var bitmap = new Bitmap(ClientSize.Width, ClientSize.Height);
        using (var graphics = Graphics.FromImage(bitmap)) graphics.Clear(BackColor);
        foreach (var control in Controls.Cast<Control>().ToArray())
        {
            var bounds = control.Bounds;
            Controls.Remove(control);
            control.DrawToBitmap(bitmap, bounds);
            if (control is ComboBox combo && combo.SelectedItem != null)
            {
                using var graphics = Graphics.FromImage(bitmap);
                TextRenderer.DrawText(graphics, combo.SelectedItem.ToString(), combo.Font,
                    new Rectangle(bounds.X + 5, bounds.Y + 2, bounds.Width - 30, bounds.Height - 4),
                    combo.ForeColor, TextFormatFlags.Left | TextFormatFlags.VerticalCenter);
            }
            Controls.Add(control);
        }
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        bitmap.Save(path);
    }
}

internal sealed class LevelMeter : Control
{
    private float level;
    [DesignerSerializationVisibility(DesignerSerializationVisibility.Hidden)]
    public float Level { set { level = Math.Clamp(value, 0, 1); Invalidate(); } }
    public LevelMeter() { DoubleBuffered = true; }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.Clear(Color.FromArgb(44, 50, 66));
        using var brush = new SolidBrush(level > 0.94 ? Color.Salmon : Color.FromArgb(114, 185, 251));
        e.Graphics.FillRectangle(brush, 0, 0, Math.Max(1, Width * MathF.Sqrt(level)), Height);
    }
}

internal sealed class MixerFader : Control
{
    private readonly string title;
    private readonly string unit;
    private readonly int minimum;
    private readonly int maximum;
    private int value;
    public event EventHandler? ValueChanged;
    public int Value => value;
    public MixerFader(string title, int minimum, int maximum, int value, string unit)
    {
        this.title = title; this.minimum = minimum; this.maximum = maximum; this.value = value; this.unit = unit;
        DoubleBuffered = true;
        TabStop = true;
        AccessibleName = title;
        AccessibleRole = AccessibleRole.Slider;
        Cursor = Cursors.Hand;
        Font = new Font("Segoe UI", 14, GraphicsUnit.Pixel);
    }
    private void SetValue(int next)
    {
        next = Math.Clamp(next, minimum, maximum);
        if (next == value) return;
        value = next; Invalidate(); ValueChanged?.Invoke(this, EventArgs.Empty);
    }
    protected override void OnMouseDown(MouseEventArgs e) { Focus(); Capture = true; Drag(e.Y); }
    protected override void OnMouseMove(MouseEventArgs e) { if (Capture) Drag(e.Y); }
    protected override void OnMouseUp(MouseEventArgs e) { Capture = false; }
    private void Drag(int y)
    {
        var scale = DeviceDpi / 96f;
        var height = Height / scale;
        SetValue(minimum + (int)Math.Round((height - 24 - y / scale) * (maximum - minimum) / (height - 86)));
    }
    protected override bool IsInputKey(Keys keyData) => keyData is Keys.Up or Keys.Down || base.IsInputKey(keyData);
    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Up) SetValue(value + 1);
        if (e.KeyCode == Keys.Down) SetValue(value - 1);
        base.OnKeyDown(e);
    }
    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var scale = DeviceDpi / 96f;
        e.Graphics.ScaleTransform(scale, scale);
        var width = Width / scale;
        var height = Height / scale;
        using var white = new SolidBrush(Color.FromArgb(235, 239, 250));
        using var accent = new SolidBrush(Color.FromArgb(137, 153, 255));
        using var pen = new Pen(Color.FromArgb(66, 75, 96), 2);
        using var centered = new StringFormat { Alignment = StringAlignment.Center };
        e.Graphics.DrawString(title, Font, white, new RectangleF(0, 8, width, 27), centered);
        e.Graphics.DrawString($"{value} {unit}", Font, accent, new RectangleF(0, 35, width, 24), centered);
        var x = width / 2;
        var top = 62;
        var bottom = height - 24;
        e.Graphics.DrawLine(pen, x, top, x, bottom);
        for (var i = 0; i < 5; i++)
        {
            var y = top + i * (bottom - top) / 4;
            e.Graphics.DrawLine(pen, x - 22, y, x - 12, y);
            e.Graphics.DrawLine(pen, x + 12, y, x + 22, y);
        }
        var thumb = bottom - (value - minimum) * (bottom - top) / (maximum - minimum);
        e.Graphics.FillRectangle(accent, x - 17, thumb - 8, 34, 16);
        e.Graphics.DrawLine(Pens.White, x - 11, thumb, x + 11, thumb);
        if (Focused) ControlPaint.DrawFocusRectangle(e.Graphics, new Rectangle(3, 3, (int)width - 6, (int)height - 6));
    }
}
