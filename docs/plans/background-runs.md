# Background runs: driving an application nobody can see or touch

Status: **Proposed 2026-10-11, not started.** Written from a MainFrame session in which the person at the desk and an
agent driving MainFrame with `ottermate` kept getting in each other's way. It widens the one-line *Headless running*
entry in [todo.md](todo.md) §3.

## The problem

An agent verifying a change runs `ottermate --launch <app>`. The application opens a real window on the user's
desktop, and the two people at the machine get in each other's way:

- **If the user is typing elsewhere**, Windows won't hand the new window the foreground. The window lands behind,
  and the first `settle` answers before anything has been drawn (`ok v0`, then `tree` says `(no tree yet)`).
- **If the user is not typing**, the window takes the foreground over whatever they were reading, and they have to
  Alt+Tab it away. The agent gets a proper run, and the user is annoyed.
- Either way the window can be clicked, typed into and focused by the user, in the middle of a script that is
  pressing keys of its own.

## What is wanted

A **background run**: the application runs its real frame loop and presents real frames, and is driven only through
the automation socket.

- It may have a window, and may even show in the taskbar.
- It is invisible to the user. It never takes the foreground, never appears on top, and can't be clicked, typed into
  or focused.
- `shot`, `tree`, `click`, `type` and `settle` behave exactly as they do in a visible run. A picture taken in the
  background is the same picture.
- It is one switch for whoever launches the application, e.g. `--automation=0 --background`, or an `ottermate
  --background --launch ...` that passes it on. It is not something each application wires itself.

## What is already known

1. **Done.** `Gui.frameOwed` is now a count (`Owed`) that starts owing the first frame, and `onWork` delivers a
   pending wake to the new listener instead of clearing it. The same change closed the gap where `settle` read
   the wake flag after the frame had cleared it but before it published, or while the click's handler ran.
   What follows is the original finding.

   **The first `settle` races the first frame, whatever the foreground is doing.** `settle` waits only while
   `Gui.frameOwed()` is true. Building the tree before the window exists sets that flag, and then `Gui.onWork`
   (called when `GuiApp.run` wires the loop) deliberately clears it. Until frame #1 publishes, "nothing has been
   published yet" reads the same as "settled". In a MainFrame probe trace (`target/logs/vexelray-probe.csv`), the
   first ottermate command reached the app 35 ms before `frame.present #1`. Fix this first, because a background
   run will hit it every time:
   - `onWork` should call the new listener once and keep the flag set when it was already set, instead of clearing it.
   - As a safeguard, `settle` should treat "no layout ever published" as owed.

   This may also explain "it never renders until it comes to the front". If the loop parks after losing that first
   wake, nothing wakes it until an OS message arrives, and being activated is such a message. The probe's
   `loop.park` / `wake` marks will say whether that is what happens.
2. **Windows are shown with `SW_SHOW`**, which activates them (`vexelray-os-windows` `Win32Window.java:502`). A
   background run wants a window that is shown without being activated, or not shown at all.
3. **An occluded window presents nothing.** DXGI returns `STATUS_OCCLUDED` (`dxgi-bridge` `Dxgi.java:45`) when the
   window is not visible. Check what the loop does on that code. A loop that skips layout or publishing while
   occluded would leave a hidden window's tree stale. `shot` reads the GUI's own render bundle rather than the
   screen, so drawing must go on even when presenting does not.
4. **Input from the OS has to be off.** Tactroller polls real devices and gates on focus. In a background run the
   only input should be what the socket injects, which already goes onto the GUI's bus without the OS (`Automation`'s
   cursor), so the OS backend should simply not be attached.

## Ways to hide the window (Windows), to try in this order

- **Cloak it**: `DwmSetWindowAttribute(hwnd, DWMWA_CLOAK, TRUE)`. The window exists and keeps its size, but DWM
  doesn't compose it, the user can't see it, and it isn't hit-testable. Combine with `WS_EX_NOACTIVATE` and showing
  it without activating (`SW_SHOWNOACTIVATE`), so it never takes the foreground. Question: does a cloaked flip-model
  swapchain report occluded, and does that stop the loop (point 3)?
- **Put it where no monitor is**: create it at a position outside every monitor, not activated, with
  `WS_EX_NOACTIVATE`. Simple, but the user can still reach it through Alt+Tab or the taskbar. `WS_EX_TOOLWINDOW` takes
  it out of both, at the cost of the taskbar button.
- **Make it transparent to the pointer**: `WS_EX_LAYERED | WS_EX_TRANSPARENT` with alpha 0. This is probably
  incompatible with a flip-model DXGI swapchain, so try it last.
- **Truly headless** (the todo entry as written): present to an offscreen target with no window at all. This is the
  cleanest, but most code depends on having a window: `GuiApp`, `WindowControls` and capture. The cloak should give
  nearly the same result for a fraction of the work, and it can come first.

Whichever is chosen, a background window must also never be raised by the framework later. `AppWindow.show()`
calls `focus()`, and so does opening a dialog. In background mode, "show" has to mean "make it exist" and nothing
more.

## How to know it works

A probe run while the user keeps typing into another application:

- no change of foreground at any point (log `GetForegroundWindow` before and after);
- the first `settle` returns only after `frame.present #1`;
- `shot` gives the same pixels as a visible run;
- a real mouse click and real keystrokes anywhere on the screen never reach the application.

## Related: give ottermate a home of its own

Every session spends time finding ottermate: it is two modules inside vexelray-gui
(`vexelray-gui-automation`, the server side; `vexelray-gui-automation-cli`, the client). The proposal is to move it
into its own repository with a parent POM and modules: the protocol and server, the CLI, and later the background
launcher this plan describes. The server side depends on `vexelray-gui-core`, so the new repository would sit after
vexelray-gui in the install order. In return, the tool has one obvious place, its own README, and a version of its own.
