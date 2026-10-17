# Reproducing startup on a machine with no native Vulkan driver

How to make this machine behave like one that has no Vulkan driver of its own, and watch what a VexelRay application
does there. It was written to explain a report from an older computer: the window opened with its dark background,
showed **Not Responding**, and stayed that way. Everything below was run on 2026-10-16 against
`calculator-vexel-demo`, on Windows 11 with an RTX 5070 Ti laptop GPU and Intel graphics, Vulkan loader 1.4.350, and
the Compatibility Pack at 1.2608.3.0. The output is real; only long lines have been cut.

The scripts are beside this guide, in [`no-native-vulkan/`](no-native-vulkan/), and the commands below are run
from that folder with the stack's checkouts side by side:

- [`watch-startup.ps1`](no-native-vulkan/watch-startup.ps1) launches the application and watches its main window the
  way Windows does: when it is visible, when Windows considers it hung, when the process exits. It can cap the
  process's CPU, and takes a thread dump when the window is first flagged hung.
- [`capture-debug-output.ps1`](no-native-vulkan/capture-debug-output.ps1) captures `OutputDebugString`, which is where
  the D3D12 debug layer reports.

---

## 1. Why the window can be "Not Responding" at all

Windows calls a window hung when its thread has not looked at its message queue for 5 seconds. The main window is
shown the moment it is made (`Win32Window`'s constructor calls `show()` and pumps once), and `GuiApp`'s constructor
makes it early:

1. `new VulkanInstance` (no window yet)
2. the main window, created and shown ([`GuiApp.java`](../../vexelray-gui-core/src/main/java/dev/vexelray/gui/core/app/GuiApp.java) `create(config)`)
3. device selection against its surface, the device, the DXGI context, the font atlases, the window's own setup
4. the framework's `ATTACH` phase, then `RUN`, whose loop is the first thing that pumps messages again

So everything in steps 3 and 4 runs with the window on screen and nobody answering it. On this machine that is about
a second. Anything that makes it longer than 5 seconds produces the report: a slow device, a slow CPU, or a driver
call that blocks.

## 2. Ways to take Vulkan away

Each of these is an environment variable on the process, so nothing on the machine changes and every other
application is unaffected. Set `VK_LOADER_DEBUG=error,warn,driver` beside any of them to see which drivers the loader
found, ignored and used.

| What it imitates | How | What the application does |
| --- | --- | --- |
| No Vulkan driver at all | `VK_LOADER_DRIVERS_DISABLE='*'` | `vkCreateInstance failed: VkResult -9` (`INCOMPATIBLE_DRIVER`) in about 0.2 s, before any window exists. Exit code 1 |
| Drivers, but no device | `VK_LOADER_VENDOR_ID_FILTER=0xfffe` (a vendor nobody has) | `IllegalStateException: no graphics+present device` from `GuiApp`. The window has been made, and closes as the main thread dies. Exit code 1 |
| No `vulkan-1.dll` | not reproduced: it would mean changing System32 | from the code, `VkLoader`'s static lookup fails inside `new VulkanInstance`, before the window |
| **Vulkan only through D3D12** | dzn, below | **the report's symptom** |

The first two are clean failures, but silent ones in a release build: it has no console, so the reason is only in
`~/.<app>/logs/<app>.log`.

## 3. Vulkan only through D3D12: dzn

An older GPU often has a D3D12 driver and no Vulkan driver: Intel graphics before Skylake is the common case. With
Microsoft's **OpenCL, OpenGL, and Vulkan Compatibility Pack** installed (Microsoft Store, package
`Microsoft.D3DMappingLayers`), the loader finds Mesa's Vulkan-on-D3D12 driver, dzn, and that is the machine's only
Vulkan. On a machine with no GPU driver at all, D3D12 is WARP, Microsoft's CPU renderer, and dzn runs on that.

Install the pack, then check it is visible:

```powershell
Get-AppxPackage *D3DMappingLayers* | Select-Object Name, Version
```

Two environment variables make this machine that machine. `VK_LOADER_DRIVERS_SELECT` keeps every other driver away,
and `DZN_DEBUG=warp` makes dzn use WARP rather than a real GPU:

```powershell
$env:VK_LOADER_DRIVERS_SELECT = '*dzn*'
$env:DZN_DEBUG = 'warp'
$env:VK_LOADER_DEBUG = 'error,warn,driver'
cd calculator-vexel-demo
mvn -q compile exec:exec -Dautomation=0
```

The loader says which device it used:

```
[Vulkan Loader] WARNING | DRIVER: Driver "nv-vk64.json" ignored because not selected by env var 'VK_LOADER_DRIVERS_SELECT'
[Vulkan Loader] WARNING | DRIVER: Driver "igvk64.json" ignored because not selected by env var 'VK_LOADER_DRIVERS_SELECT'
[Vulkan Loader] DRIVER:                Using "Microsoft Direct3D12 (Microsoft Basic Render Driver)" with driver: "C:\Program Files\WindowsApps\Microsoft.D3DMappingLayers_1.2608.3.0_x64__8wekyb3d8bbwe\x64\vulkan_dzn.dll"
20:34:31.852 INFO  [main] gui.app - presenting through dxgi
20:34:32.101 DEBUG [main] framework.shell - phase RUN
20:34:33.101 ERROR [main] uncaught - uncaught exception in thread main
dev.supirvast.ffi.NativeException: vkEndCommandBuffer failed: VkResult -1
	at dev.supirvast.vulkan.Ffm.check(Ffm.java:83)
	at dev.vexelray.present.dxgi.DxgiPresenter.record(DxgiPresenter.java:307)
	at dev.vexelray.present.dxgi.DxgiPresenter.renderFrame(DxgiPresenter.java:250)
	at dev.vexelray.present.dxgi.DxgiPresenter.render(DxgiPresenter.java:207)
	at dev.vexelray.gui.core.app.GuiWindow.pull(GuiWindow.java:293)
	at dev.vexelray.gui.core.app.GuiWindow.frame(GuiWindow.java:264)
	at dev.vexelray.gui.core.app.GuiApp.run(GuiApp.java:1607)
```

Startup succeeds all the way through device selection, DXGI and `RUN`; the first frame fails. Leave out
`DZN_DEBUG=warp` and dzn runs on the RTX instead, and fails at the same place in the same way, so the failure is
dzn's and not WARP's. Section 5 says why.

## 4. Imitating an older machine

On this machine WARP is fast enough that the window is up for half a second. `watch-startup.ps1 -CpuPercent` caps the
whole process tree with a Windows job object, which is the closest thing to a slower computer without one:

```powershell
$env:VK_LOADER_DRIVERS_SELECT = '*dzn*'
$env:DZN_DEBUG = 'warp'
.\watch-startup.ps1 -Dir ..\..\..\..\calculator-vexel-demo -Seconds 500 -Out $env:TEMP\dzn -CpuPercent 0.5 -MvnArgs '-q','compile','exec:exec','-Dautomation=0'
```

```
capped at 0.5% of the machine (20 logical processors)
  0.03s  no visible window
 62.91s  window 'Calculator' hung=True
 62.91s  thread dump while hung -> ...\dzn.threads
 63.41s  window 'Calculator' hung=False
 68.34s  window 'Calculator' hung=True
 70.49s  window 'Calculator' hung=False
 72.28s  no visible window
 73.45s  process exited
```

Across caps, with dzn on WARP:

| CPU cap (of 20 logical processors) | Window visible | Flagged hung | Input backend to `RUN` | Then |
| --- | --- | --- | --- | --- |
| none | about 0.5 s | no | 3.0 s | first frame fails |
| 5% (about one core) | about 0.5 s | no | 3.6 s | first frame fails |
| 1% | about 4.6 s | no, just under the limit | 9.5 s | first frame fails |
| 0.5% | about 9.4 s | **yes**, twice | 16.6 s | first frame fails at about 73 s |

**The cap overstates it.** A hard cap works by suspending the job's threads for the rest of each scheduling interval,
so a thread can be paused for long enough to be flagged hung whatever it was doing. The dump taken at the first flag
above caught the main thread inside the window's own constructor, in its one `PeekMessage`, which is the cap and not
the code. Read the table as "the unanswered stretch grows with how slow the machine is", not as a measurement of any
particular machine.

The healthy case is the same script with no environment set and no cap: the window is up and is never flagged.

## 5. Reading the D3D12 debug layer

`VkResult -1` is `VK_ERROR_OUT_OF_HOST_MEMORY`, and memory is not what ran out. The debug layer below shows invalid
commands recorded into the D3D12 command list; a command list with those in it refuses to close, and the -1 is dzn
reporting that from `vkEndCommandBuffer` (read from the symptoms, not from dzn's source). The D3D12 debug layer says
what was invalid. It needs Windows' *Graphics Tools* optional feature
(`C:\Windows\System32\d3d12SDKLayers.dll`), dzn turns it on with `DZN_DEBUG=d3d12`, and it reports through
`OutputDebugString`:

```powershell
Start-Process powershell.exe -ArgumentList '-NoProfile','-File','.\capture-debug-output.ps1','-Out',"$env:TEMP\d3d12.txt",'-Seconds','45' -WindowStyle Hidden
$env:VK_LOADER_DRIVERS_SELECT = '*dzn*'
$env:DZN_DEBUG = 'warp,d3d12'
.\watch-startup.ps1 -Dir ..\..\..\..\calculator-vexel-demo -Seconds 40 -Out $env:TEMP\dzn-debug -MvnArgs '-q','compile','exec:exec','-Dautomation=0'
Select-String 'ERROR' $env:TEMP\d3d12.txt
```

```
D3D12 ERROR: ID3D12CommandList::Barrier: Invalid LayoutAfter D3D12_BARRIER_LAYOUT_RENDER_TARGET for simultaneous access
  texture in texture barrier at group [1], index [0]. Barrier layouts for textures created with flag
  RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS are always D3D12_BARRIER_LAYOUT_COMMON. [ STATE_SETTING ERROR #1334: INCOMPATIBLE_BARRIER_LAYOUT]
D3D12 ERROR: ID3D12CommandList::Barrier: Invalid LayoutBefore D3D12_BARRIER_LAYOUT_RENDER_TARGET for simultaneous access texture ...
```

(Two `D3D12SDKVersion(614) ... != requested` lines come first. They are the debug layer and the runtime disagreeing
about versions, and are not the failure.)

The texture is the image the presenter draws into and shares with DXGI: the only simultaneous-access texture in the
frame, and presumably made so by dzn because it is exported. dzn then translates the render pass's layout transitions into D3D12 layouts that a
simultaneous-access texture is not allowed to have. It is not about which layout the application asks for: drawing
into it in `VK_IMAGE_LAYOUT_GENERAL` instead of `COLOR_ATTACHMENT_OPTIMAL` was tried, and dzn turns that into
`D3D12_BARRIER_LAYOUT_DIRECT_QUEUE_COMMON`, which is refused the same way.

dzn draws correctly into an image it does not share. With the same environment, `vexelray-shadelets`'
`EffectsRenderTest` (9 tests, offscreen canvas rendering) passes on dzn over WARP, none skipped:

```bash
cd vexelray
VK_LOADER_DRIVERS_SELECT='*dzn*' DZN_DEBUG=warp mvn -o -pl vexelray-shadelets test -Dtest=EffectsRenderTest
```

## 6. Afterwards

The variables are per-process, so closing the shell undoes them. The Compatibility Pack stays installed until it is
removed in Settings → Apps. While it is installed, the loader offers dzn's devices beside the native ones to every
Vulkan application, and device selection decides which is used.
