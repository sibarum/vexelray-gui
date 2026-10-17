# Writes every OutputDebugString in this login session to -Out for -Seconds, without a debugger attached. It is the
# DBWIN protocol DebugView uses. The D3D12 debug layer reports there and nowhere else, so this is how its messages
# are read when dzn is run with DZN_DEBUG=d3d12. Start it first, in its own process, then launch the application:
#
#   Start-Process powershell.exe -ArgumentList '-NoProfile','-File','capture-debug-output.ps1','-Out','d3d12.txt' -WindowStyle Hidden
#
# Only one reader of DBWIN can run at a time; close DebugView or a debugger first.
param([Parameter(Mandatory)][string]$Out, [int]$Seconds = 60)

Add-Type @"
using System; using System.IO; using System.IO.MemoryMappedFiles; using System.Text; using System.Threading;
public static class DbWin {
    public static void Run(string path, int seconds) {
        bool created;
        using (var ready = new EventWaitHandle(false, EventResetMode.AutoReset, "DBWIN_BUFFER_READY", out created))
        using (var data = new EventWaitHandle(false, EventResetMode.AutoReset, "DBWIN_DATA_READY", out created))
        using (var map = MemoryMappedFile.CreateOrOpen("DBWIN_BUFFER", 4096))
        using (var view = map.CreateViewAccessor(0, 4096))
        using (var w = new StreamWriter(path, false, Encoding.UTF8)) {
            w.AutoFlush = true;
            DateTime end = DateTime.Now.AddSeconds(seconds);
            byte[] text = new byte[4092];
            ready.Set();
            while (DateTime.Now < end) {
                if (!data.WaitOne(500)) continue;
                // The buffer is the writer's pid, then its message, NUL-terminated.
                int pid = view.ReadInt32(0);
                view.ReadArray(4, text, 0, text.Length);
                int n = Array.IndexOf(text, (byte) 0);
                w.WriteLine(pid + ": " + Encoding.Default.GetString(text, 0, n < 0 ? text.Length : n).TrimEnd());
                ready.Set();
            }
        }
    }
}
"@
[DbWin]::Run($Out, $Seconds)
