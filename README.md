<p align="center">
  <img src="docs/branding/vexelray-logo.jpg" alt="VexelRay — pure-SDF renderer for 2D + 3D" width="360">
</p>

# vexelray-gui

A retained-mode GUI framework for the VexelRay engine. Declarative trees, flex layout, relative
units, live mutation from any thread — rendered as **one batch of one SDF uber-shader**,
shadows, lighting, and text included. A window showing no images is one draw call; one showing
a ray-marched **viewport** costs a rebind per image and nothing else, because a viewport is a box
that samples rather than a second pipeline.

<p align="center">
  <img src="docs/demo.png" alt="The gallery demo: a chapter rail, a live editor page, and the activity rail" width="720">
</p>

It is the top of a three-sibling stack, each its own repo:

| Repo | Role |
| --- | --- |
| **[vexelray](https://github.com/sibarum/vexelray)** | Pixels: Vulkan runtime, the 2D `Canvas`, MSDF text, the SDF uber-shader (pre-compiled to SPIR-V at build time via [SupirVast](https://github.com/sibarum/supirvast)) |
| **[tactroller](https://github.com/sibarum/tactroller)** | Input: keyboard/pointer/clipboard middleware — *every* device event flows through it |
| **[atchung](https://github.com/sibarum/atchung)** | Messages: the typed bus that carries input in and mutations through |

The GUI's job is what sits above pixels and wire: **identity, layout, dispatch, motion**
([docs/architecture.md](docs/architecture.md) is the deep version of this document).

## Building

The siblings install to the local Maven repo, in dependency order:

```bash
cd ../supirvast   && mvn install
cd ../vexelray    && mvn install
cd ../tactroller  && mvn install    # and atchung, if not pulled in transitively
cd ../vexelray-gui && mvn install
```

Run the interactive showcase (needs a Vulkan-capable GPU):

```bash
mvn -pl vexelray-gui-demo -am compile exec:exec
```

Headless capture to PNG (works in CI, no input backend needed):

```bash
mvn -pl vexelray-gui-demo exec:exec -Ddemo.args=--capture
```

Native executable (needs a GraalVM JDK; profile-gated so ordinary builds stay fast):

```bash
mvn -Pnative -pl vexelray-gui-demo -am package   # -> target/vexelray-demo(.exe)
```

## The mental model

Three rules explain almost everything:

1. **Handles are write-only and thread-safe.** A `Node` is a stable identity, not the node itself.
   Every setter posts a mutation onto the bus; the GUI thread applies it. You can hold a handle
   forever and mutate from any thread — that is the normal way, not the exception.
2. **The GUI thread is the main thread; app logic is not.** Handlers (`onClick`, `onChange`, …)
   run on worker threads and talk back through handles. Vulkan, the window, and presentation stay
   on main.
3. **Layout owns geometry.** Everything is a `Length` resolved by the flex pass; the renderer is a
   pure consumer of resolved pixels. Reads come from a published snapshot (`node.layout()`), one
   frame stale by design.

## Building a UI

```java
Gui gui = new Gui();

Theme theme = gui.theme();          // colour is a role, never a literal (see Theme, below)

Node title = gui.text("Hello").textSize(Length.rem(1.75f)).textColor(theme.color(Role.INK));

Node card = gui.column()
        .width(Length.FILL).height(Length.FILL)
        .background(theme.color(Role.PANEL))
        .corner(Length.rem(1))
        .border(Length.rem(0.1f), theme.color(Role.LINE))
        .lit(true)                          // SDF edge light + vertical gradient
        .elevation(Length.rem(1.25f))       // analytic soft shadow underneath
        .padding(Length.dp(20)).gap(Length.rem(0.625f))
        .children(title);

gui.root().background(theme.color(Role.PAGE)).children(card);
```

**Containers**: `gui.row()`, `gui.column()`, `gui.box()`, `gui.text(s)`; compose with
`.children(...)`, mutate structure later with `append`/`visible(false)` (hiding keeps identity,
handlers, and widget state — the right tool for tab pages and anything shown one-at-a-time).

**Lengths**: `rem`/`em` (type-relative — these zoom), `dp` (density-independent frame — gutters
and chrome that should *not* zoom), `percent`, `vw`/`vh`, `grow(f)` (flex weight), `FILL`, `AUTO`
(size to content). Corner radius, border, elevation, and text size are all `Length`s, so the whole
UI scales coherently: `gui.zoomRange(0.5f, 3f, 1.25f)` plus `gui.zoomIn/zoomOut/resetZoom`.

## Theme

Colour is a **role**, resolved against the one `Theme` on `Gui` — no widget names a colour:

```java
gui.theme(Theme.LIGHT);                                            // before building; DARK is the default
Node card = gui.box().background(gui.theme().color(Role.PANEL))    // PAGE / CHROME / PANEL / RAISED / WELL / LINE
        .border(Length.rem(0.1f), gui.theme().color(Role.LINE));   // INK / DIM / FAINT / ACCENT / ACTION / DANGER
gui.onState(card, s -> card.background(gui.theme().color(Role.PANEL, s)));   // hover and press are derived
```

A `Palette` is nine authored numbers, not a table of colours: a page, a lightness step, an ink, a
fade, three chromatic anchors, and how depth reads. Surfaces are `n` steps from the page **towards
the ink**, so one set of roles serves a dark and a light theme and a border keeps the same
perceptual contrast in both — the theme never states a direction. Hover and press are one signed
lightness step applied in Oklab to whatever the control already is, which is why there is no
`PANEL_HOVER`. A role is a function (`Role mine = p -> p.surface(3)`), so an application extends the
vocabulary without registering anything, and `PaletteGuardTest` fails the build if any framework
class mints a `Color` of its own.

## Depth and light

Every visual effect is a transfer function over the one rounded-box SDF the renderer already
evaluates — no textures, no extra passes, still one draw:

| Prop | Effect |
| --- | --- |
| `.elevation(Length)` | Soft drop shadow under the background; animate it per interaction state (the demo's buttons lift on hover and set down flush while pressed) |
| `.lit(true)` | Edge light from the global top-left light + a faint vertical luminance gradient — modulates whatever `background` is set to, so state restyles keep working |
| `.textSunken(true)` | Letterpress text: shade above the glyphs, glint below, crisp fill — for a `Role.ON_ACTION` label on a filled control |
| `.corner(top, bottom)` | Independent corner radii per vertical half — a tab is `corner(r, Length.ZERO)` |
| `.opacity(float)` | Fades the node and its whole subtree, multiplying down the tree. A visual transform, never a layout input: nothing reflows, which is what makes it cheap enough to drive every frame. Pair it with `.hitInert(true)` when fading something out from under the pointer — a faded node still occupies its box |
| `.translate(emX, emY)` | Draws the node and its subtree offset, in multiples of its own em, without moving it. The other visual transform: no reflow, no measurement. It still lays out, reports and hit-tests where it was, so pair it with `.hitInert(true)` while it moves and `.clip(true)` on the parent unless it should escape |
| `.clip(true)` | Masks children to this node's border box. Scrolling containers already clip their viewport; say this for one that doesn't scroll but still has an edge — a translated child adds no overflow, so it never trips the automatic kind |

The design rule that produced these (and deleted one that didn't fit): effects must compose against
the **global light**, not accumulate as geometry decorations. Multi-pass effects (backdrop blur,
bloom) are deliberately out of scope for the single-pass batch.

## Interaction

Input arrives tactroller → atchung → the framework's dispatch; you subscribe by node:

```java
gui.onClick(button, () -> log.append(gui.text("clicked")));       // worker thread
gui.onContextClick(row, e -> menu.show(e.x(), e.y()));            // right-click, with the pointer position
gui.onState(button, s -> button.background(gui.theme().color(Role.PANEL, s))); // observers add up
gui.onDrag(slider, e -> set(e.fractionX()));                      // pointer-captured
gui.shortcut(Key.EQUAL, gui::zoomIn, Modifier.CONTROL);           // global chords
gui.claim(node, Shortcut.of(Key.LEFT), ClaimScope.FOCUSED, cmd);  // focused-only chords
```

Clickable nodes get the pointer cursor by inference; register `gui.cursor(node, shape)` only when
the affordance can't be inferred (a slider wants GRAB). Focus is `gui.focusable(node, true)` +
`gui.focus(node)`; Tab order and focused-scope claims come with it.

### Context menus

A menu is not an object you build and keep — it is a question asked at the moment of the right click:

```java
gui.onContextMenu(row, menu -> menu
        .item("Open", () -> open(path))
        .item("Paste", clipboard.hasText(), () -> paste(path))   // shown, greyed when it cannot apply
        .separator()
        .item("Properties", () -> inspect(path)));
```

- **The nearest node with a menu owns the click** — leaf→root, the same rule a click handler follows,
  so a row inside a panel inside a page may each declare one and the innermost answers.
- **Sources accumulate**, like `onState` observers: the widget states its defaults and the
  application adds what only it knows, into one menu, in registration order. Nothing is restated and
  nothing has to agree on an order.
- **A widget can hand its own context to the builder**: `TreeView.onContextMenu((item, menu) -> …)`
  and `Tabs.onContextMenu((index, menu) -> …)` say *which* thing was clicked, which the framework has
  no way to name.
- **Contribute nothing and no menu opens** — "there is nothing to offer here" needs no special case.
- **Defaults ship with the widgets**: a `TextField` offers Copy/Cut/Paste (Copy alone when
  `readOnly(true)`, each greyed when it does not apply), a tab offers Close.

`ContextMenu` draws them; `gui.menus(presenter)` replaces it with something else entirely
(`MenuPresenter`), the same way `gui.clipboard(...)` replaces the clipboard.

**Threading**: `gui.async(work)` runs app logic off the GUI thread; `gui.batch(edits)` groups
mutations into one frame. Install the OS clipboard with `gui.clipboard(...)` (see the demo — the
in-memory default keeps headless runs working).

## Widgets (`vexelray-gui-widget`)

Widgets are ordinary framework users — built entirely on public `Node`/`Gui` API:

- **`TextField`** — single or `multiline(true)` editing: word wrap, line numbers, caret-follow
  scroll, selection, cut/copy/paste, sticky-column Up/Down, `onSubmit`. Formatting `Span`s
  (fg/bg/underline over character ranges) auto-remap through every edit — set them once, they
  follow their text. `readOnly(true)` closes the *user's* edit channel and keeps the caret, the
  selection and Copy — a field to read out of, which the application still writes to.
- **`Slider`** — a dragged track with a lit, elevated thumb; `value()`/`onChange`.
- **`Tabs`** — headers over a page stack. Pages are hidden, never removed, so switching away and
  back returns the page exactly as it was — caret and all. Arrow keys walk the bar while a header
  has focus; the active tab floats forward (lit + elevated, rounded shoulders, flat seat).
- **`TreeView<T>`** — a generic explorer for hierarchical data (a filesystem, an AST) over a
  four-method `Source<T>`. Children fetch lazily off the frame loop, exactly once per item;
  collapse hides the subtree rather than discarding it; one tab stop drives the whole tree
  (Up/Down/Left/Right/Home/End/PageUp/PageDown/Enter).
- **`ContextMenu`** — the panel a menu is drawn as, and nothing more: what is *on* a menu and whose
  menu it is are dispatch's (see **Context menus** below). Built on floating placement
  (`Node.floatAt` — an out-of-flow last child of the root paints over the page and is hit first: the
  overlay primitive). Escape and click-away dismiss; opening reflows nothing; an edge open slides
  on-screen. It installs itself, so the default menus work without the application wiring anything.
- **`TitleBar`** — the window's own chrome as ordinary widgets: a draggable strip, a title, and
  minimize/maximize/close buttons. Two declarations do the work — the strip is `WindowRegion.DRAG`,
  each button punches an `INTERACTIVE` hole in it — so the window manager still moves, snaps and
  maximizes the window while the clicks reach the buttons. The maximize icon is re-derived from the
  window on every viewport change, because Win+Up and a caption double-click change it too.
- **`Popout`** — a panel docked against one edge of the window, collapsible to a rail and poppable out into a
  window of its own. Docked, it is an ordinary child of the host tree: the host's content shrinks by exactly
  its width, and it clips and scrolls with the window. Popping out is **not** a reparent — a `Node` belongs to
  the tree that minted it — so the content is declared as a builder and run once per tree, both instances then
  hidden rather than removed. Collapsed and popped-out leave the same footprint, the rail carrying the control
  that undoes it; a `Reveal` makes a landmark inside the panel reachable, docking it back if it was out. The
  window is a named `AppWindow` wearing the application's own `TitleBar` (`Decorations.CLIENT`), with the dock
  control in its leading slot, and every route out of the window — dock button, close box, Alt+F4, the owner
  going away — is heard in one place.
- **`Tooltip`** — hover help that is admissible under the hover rule by construction: the bubble is
  `hitInert` (drawn, never a pointer target), anchored to the control's box (never follows the
  pointer), and coexists with the control's own hover restyle because state observers accumulate.

The demo is a **gallery**: a navigation rail, a page, and an activity rail, with one
[chapter](vexelray-gui-demo/src/main/java/dev/vexelray/gui/demo/Chapter.java) per part of the
framework — text, trees, drag and drop, drawing, plot, typeset, windows, motion, layout. Each is a
file of its own under `demo/chapter`, so adding a subsystem to the demo is adding a file to
[Gallery.java](vexelray-gui-demo/src/main/java/dev/vexelray/gui/demo/Gallery.java) rather than
another button to one screen. [Demo.java](vexelray-gui-demo/src/main/java/dev/vexelray/gui/demo/Demo.java)
is what is left over: the application edge — input, clipboard, window memory, the frame loop, and
what closing the window means — which is exactly the part a client application writes for itself.

Run it with `mvn -pl vexelray-gui-demo exec:exec`. `Demo --capture out.png <chapter>` shoots a
single page headless, and `Demo --capture-zoom` walks the zoom ladder as a strip of images.

## The application edge

What sits between the GUI and the OS, all driven from the one main-thread loop:

- **Windows** — `GuiApp(WindowConfig)` creates the main window (at persisted bounds, if you pass
  them); `requestPopup(...)` is callable from any thread and materialises a true OS window into
  the shared frame loop. Popups are **owned** by the main window: one taskbar icon for the whole
  application, always above the main window, raised and minimized together with it.
- **Window chrome** — `WindowConfig.decorations(Decorations.CLIENT)` hands the frame to the GUI:
  the client area covers the whole window, so a `TitleBar` draws where the system title bar was.
  The window keeps its overlapped frame, so dragging, snapping, Win+arrow, double-click-to-maximize,
  the system menu and the maximize clamp to the work area stay the window manager's. What the GUI
  supplies is geometry, not behaviour: nodes declare `WindowRegion.DRAG` / `INTERACTIVE` /
  `MAXIMIZE_BUTTON`, the host derives the rectangles from each laid-out frame and pushes them to
  the OS, and the window answers its own hit-test from them. `GuiApp.controls()` is the other half —
  minimize, maximize/restore, close, for the buttons to call.
- **Named windows** — `app.window("terminal", spec)` is one window however many times it is asked for:
  `show()` creates it if it is closed and raises and focuses it if it is not, `hide()` takes it off
  the screen without losing it, `toggle()` is the shortcut version, and the `Gui` outlives every
  open/close cycle, so a window reopened is the window as it was left. Commands are safe from any
  thread — they are performed at the top of the next frame. `app.input(factory)` is the other half:
  supply once how a device backend attaches to a window, and every window the framework opens from
  then on is interactive without further wiring.
- **Modal dialogs** (`Modals`, `Modal`) — `Modals.show(Modal.of(title, message).button(...))` from
  any thread, no class per question. Each dialog is a real OS window with the same chrome as the
  rest of the application, and while it is up every other window is **disabled by the window
  manager** (not merely ignoring events) and dimmed by a scrim that reflows nothing and takes no
  clicks. Two questions asked at once are shown one after the other, never stacked.
- **Close interception** — `app.onCloseRequest(request -> ...)` turns a close into a question the
  application answers whenever it can: the window stays open and fully live (which is what lets the
  answer come from a dialog), and `request.proceed()` / `request.cancel()` decide. Per window
  through `WindowSpec.onCloseRequest`. A window that was *destroyed* rather than asked is never put
  to a vote.
- **`AppHome` / `Settings`** — per-user persistence. `AppHome.of("appname")` is the directory the
  application owns, `~/.appname/`, on every platform: `settings()` is `settings.properties`,
  `settings("session")` a second store beside it, `file(...)`/`folder(...)` any other path inside it
  (resolution that leaves the directory is rejected, not followed). Reading creates nothing — the
  directory appears at the first write — and the location is redirectable for portable installs and
  tests, by `AppHome.at(path)` or the `appname.home` system property. `Settings` itself is typed
  get/put (including ordered lists, e.g. the open files), explicit atomic `save()`, and every failure
  mode (missing, malformed, corrupt) degrades to defaults — never an exception at launch. The demo
  round-trips its window bounds through `~/.vexelray-demo/session.properties`.
- **`WindowMemory`** — where each window was, across restarts, on top of `Settings`. Name a window and
  it gets three lines: `config(key, title, w, h)` for the `WindowConfig` it is *created* with,
  `restoreBounds` in `onCreated` for a reopen (a named window's spec is built once, so its config is a
  stale rectangle by then), and `watch(key, window[, gui])` to follow it. Then `poll()` each frame and
  `save()` at shutdown. It carries the four things every application otherwise reimplements: a clamp
  through `WorkArea.fit` so bounds saved on a desk that has since changed shape cannot strand a window
  off-screen; maximized kept *apart* from the bounds it would otherwise destroy; a debounce, so a drag
  is one write rather than four hundred; and open-ness *polled* rather than written on close, which is
  what makes quitting with a tool window up distinguishable from closing it by hand. Pass the window's
  `Gui` to `watch` and the user's zoom is remembered too, restored before the first frame.
- **Native file dialogs** (`vexelray-gui-nfd`) — open/save/pick-folder as `Optional<Path>`, bound
  straight to the window handle.
- **Automation** (`vexelray-gui-automation`) — two lines, and an agent or a script can drive the running
  application over a loopback socket: `AutomationServer.start(new Automation(gui, app.controls()))`, then
  `printf 'find Save\nclick save\nsettle\n' | nc localhost 7654`. The pointer **travels** rather than
  teleporting, so hover fires because it was provoked; nodes are addressed by role, name or `Gui.landmark`
  rather than by coordinate; and with `-Dprobe.format=csv` the run writes one correlation log that
  `sibarum.probe.CsvView --gaps` reads back. See [docs/automation.md](docs/automation.md).

## Going deeper

- [docs/architecture.md](docs/architecture.md) — the full design: substrate contracts, the
  retained model, dispatch, the effect system as built
- [docs/layout-read-model.md](docs/layout-read-model.md) — the geometry pipeline and the
  published read-model every consumer (renderer, hit-testing, widgets) shares
- [docs/semantic-read-model.md](docs/semantic-read-model.md) — the other half of it: what each node
  *is* (role, name, structure, focus), for readers that are not the renderer
- [docs/automation.md](docs/automation.md) — `vexelray-gui-automation`: driving the real app from an
  agent with a pointer that never teleports, and the one correlation log that explains what happened
- [docs/keyboard-focus-text.md](docs/keyboard-focus-text.md) — keys, focus, claims, and text editing
- [docs/transfer.md](docs/transfer.md) — drag and drop, cut and paste: one resolution, several
  sources, and why what is shown is always what will happen
