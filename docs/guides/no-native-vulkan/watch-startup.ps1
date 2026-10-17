# Launches an application and watches its main window the way Windows does: when it becomes visible, whether
# Windows considers it hung (IsHungAppWindow, the test behind "Not Responding"), and when the process exits.
# The first time the window is flagged hung, and again if the process outlives -Seconds, it writes a thread
# dump (jcmd) beside -Out, so the main thread's stack at that moment can be read.
#
#   -Dir         the application's checkout; the command runs there
#   -Seconds     how long to watch before stopping it
#   -Out         a path prefix: <Out>.stdout, <Out>.stderr and <Out>.threads are written
#   -CpuPercent  if above 0, a hard cap on the whole process tree's CPU, as a percentage of the machine
#                (fractions allowed): a stand-in for older hardware. See no-native-vulkan.md for its caveat.
#   -MvnArgs     what to pass to mvn (default: -q compile exec:exec)
#
# The environment is inherited, so set VK_LOADER_DRIVERS_SELECT, DZN_DEBUG and the rest before calling it.
param([Parameter(Mandatory)][string]$Dir, [int]$Seconds = 20, [Parameter(Mandatory)][string]$Out,
      [double]$CpuPercent = 0, [string[]]$MvnArgs = @('-q', 'compile', 'exec:exec'))

Add-Type @"
using System; using System.Runtime.InteropServices;
public static class StartupWatch {
    [DllImport("user32.dll")] public static extern bool IsHungAppWindow(IntPtr h);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern IntPtr CreateJobObject(IntPtr a, string n);
    [DllImport("kernel32.dll", SetLastError = true)] static extern bool SetInformationJobObject(IntPtr j, int c, ref CpuRate i, int l);
    [DllImport("kernel32.dll")] public static extern bool AssignProcessToJobObject(IntPtr j, IntPtr p);
    [StructLayout(LayoutKind.Sequential)] struct CpuRate { public uint Flags; public uint Rate; }

    // A job whose processes together get at most `percent` of the machine. Children inherit the job, so capping
    // mvn caps the JVM it starts. Rate is in hundredths of a percent; flags are ENABLE | HARD_CAP.
    public static IntPtr Capped(double percent) {
        IntPtr job = CreateJobObject(IntPtr.Zero, null);
        CpuRate rate = new CpuRate { Flags = 1 | 4, Rate = (uint)(percent * 100) };
        if (!SetInformationJobObject(job, 15, ref rate, Marshal.SizeOf(rate))) {
            throw new Exception("SetInformationJobObject failed: " + Marshal.GetLastWin32Error());
        }
        return job;
    }
}
"@

function Elapsed { "{0,6:N2}s" -f ((Get-Date) - $script:t0).TotalSeconds }

# The java processes started under $root: Maven's own JVM and the application's. Nothing else on the machine is
# watched or stopped.
function Launched([int]$root) {
    $all = @(Get-CimInstance Win32_Process -Property ProcessId, ParentProcessId, Name)
    $ids = @($root)
    do {
        $more = @($all | Where-Object { $ids -contains $_.ParentProcessId -and $ids -notcontains $_.ProcessId } |
            ForEach-Object { [int]$_.ProcessId })
        $ids += $more
    } while ($more.Count -gt 0)
    @($all | Where-Object { $_.Name -eq 'java.exe' -and $ids -contains $_.ProcessId } |
        ForEach-Object { Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue })
}

$job = if ($CpuPercent -gt 0) { [StartupWatch]::Capped($CpuPercent) } else { [IntPtr]::Zero }
$script:t0 = Get-Date
$mvn = Start-Process -FilePath mvn.cmd -ArgumentList $MvnArgs -WorkingDirectory $Dir -PassThru -WindowStyle Hidden `
    -RedirectStandardOutput "$Out.stdout" -RedirectStandardError "$Out.stderr"
if ($CpuPercent -gt 0) {
    # Before mvn has started the JVM, so the JVM is born inside the job.
    if (-not [StartupWatch]::AssignProcessToJobObject($job, $mvn.Handle)) { throw "AssignProcessToJobObject failed" }
    "capped at $CpuPercent% of the machine ($([Environment]::ProcessorCount) logical processors)"
}

$last = ''
$dumped = $false
while (((Get-Date) - $t0).TotalSeconds -lt $Seconds) {
    # MainWindowHandle is non-zero only for a visible top-level window.
    $app = Launched $mvn.Id | Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
    if ($app) {
        $hung = [StartupWatch]::IsHungAppWindow($app.MainWindowHandle)
        $state = "window '$($app.MainWindowTitle)' hung=$hung"
        if ($state -ne $last) { "$(Elapsed)  $state"; $last = $state }
        if ($hung -and -not $dumped) {
            "$(Elapsed)  thread dump while hung -> $Out.threads"
            & jcmd $app.Id Thread.print 2>&1 | Out-File -Encoding utf8 "$Out.threads"
            $dumped = $true
        }
    } elseif ($last -ne 'none') {
        "$(Elapsed)  no visible window"
        $last = 'none'
    }
    if ($mvn.HasExited) { "$(Elapsed)  process exited"; break }
    Start-Sleep -Milliseconds 50
}

foreach ($left in @(Launched $mvn.Id)) {
    "still running after $Seconds s: pid $($left.Id); thread dump -> $Out.threads"
    & jcmd $left.Id Thread.print 2>&1 | Out-File -Encoding utf8 -Append "$Out.threads"
    $left | Stop-Process -Force
}
if (-not $mvn.HasExited) { Stop-Process -Id $mvn.Id -Force }
"stopped"
