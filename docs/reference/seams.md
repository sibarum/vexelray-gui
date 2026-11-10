# Seams: where a widget runs code in the frame

**Landed.** `dev.vexelray.gui.core.spi.Seams` is the one place the GUI takes code to run on its own thread, in the
frame: a click handler, a drag, a resize observer, a widget's asked half. This page says whose it is, what is on it,
how the boundary is held, and how a widget that moves gets its asked half. Each seam's own contract is its Javadoc;
this page is the map those Javadocs do not give.

*Written 2026-11-10, after Rasterware's port nearly proposed a new door to the GUI thread for vexelray-codec's views
when this one was already theirs (Rasterware `docs/retrospective.md`, *Pictures that play*). If you are about to ask
for a way to run code in the frame, read this first.*

## Whose it is

Everything on the GUI thread is a **part**, told and asked and nothing else (vexelray-framework's `threading.md`,
T7.6). Two kinds of code are trusted to register a part's code with the GUI, and `Seams` is theirs:

| Who | What it registers | Examples |
| --- | --- | --- |
| **A widget library**: any library of widgets, in this repo or not | a widget answering its own input, observing its own geometry, drawing its own motion | `vexelray-gui-widget`, `-scroll`, `-typeset`; vexelray-codec's `ImageView` and `VideoView`; vex-suite-common's `TerminalScreen` |
| **A host**: what owns the window and the loop | the sinks the GUI reports to, and the clock and wake it runs on | vexelray-framework's shell (`Window`, `Views`, `PointerLock`, `UndoFeedback`), the automation driver |

**An application does none of this.** Its view hears a node by `gui.hear`, declares its chords and menus with the
message each sends (`keys.action(...).sends`, `gui.claim(..., sender, message)`, `gui.menu`), and draws what moves in
its `@Draw`. So no code of an application's is handed to the GUI to run in the frame; it only says ahead of time what
a press should send. Being "a widget library" is not where the code lives but what it is: code that answers its own
input inside the frame, written once and reused, held by its own tests.

### If you want to... (application, widget)

| | An application's `@View` | A widget |
| --- | --- | --- |
| hear a click, a hover, a pointer, a resize | `gui.hear(node, NodeEvent.CLICK / STATE / POINTER / RESIZE, ...)` | `onClick`, `onState`, `onPointer`, `onResize` |
| take a key or chord | `keys.action(...).sends(sender, message)`, `gui.claim(node, chord, scope, sender, message)` | `onKey`, `onChar`, `claim`, `claimAny`, `shortcut` |
| offer a context menu | `gui.menu(node, Menu)`, each item a message | `onContextMenu` (built per click) |
| move something every frame | `@Draw`, checked by the processor | `ask` an `Asked` of its own (below) |
| drag, drop | messages from the widget that does it | `onDrag`, `onDragSource`, `onDrop`, `watchDrag` |

## What is on it

`W` a widget library's, `H` a host's. Every handler runs on the GUI thread, in the dispatch or the frame, so it is
short: read, set a node, publish a message, return. Nothing on it blocks, logs in the frame, or waits on another
thread.

| Group | Seams | |
| --- | --- | --- |
| **Input** | `onClick`, `onDoubleClick`, `onContextClick`, `onKey`, `onChar`, `shortcut`, `claim`, `claimAny` | W |
| **Pointer and drag** | `onState`, `onPointer`, `onDrag`, `onWheel`, `onDragSource`, `onDrop`, `dropTargetsAt`, `watchDrag` | W |
| **Geometry and navigation** | `onResize` ("my geometry is real now", first time included), `reveals`, `scrollSource`, `scrollAxes` | W |
| **Menus and commands** | `onContextMenu`, `openMenu`; `menus(presenter)` and `commands(commands)` install what draws menus and names chords (`ContextMenu`, `Keymap` install themselves) | W |
| **The frame** | `ask`, `unask`: a part's asked half (frame-contract.md). `clock()`: the tree's clock, read | W, H |
| **Read models** | `drag()`, `modifiers()`, `resizing()` (the edge a window resize is under way from, for a grip), `viewport()`, `zoom()`, `zoomSizesWindow()`, `dpi()`, `layout()`, `semantics()`: the GUI's own state, as bus `State`s a widget restyles from | W |
| **Host sinks** | `onUndo`, `onCursorChange`, `onPointerLock`, `onPointerWarp` / `pointerWarp`, `resizing(edge)` (from the window, each frame), `onWork` (the wake: required by any loop that sleeps) | H |
| **The loop** | `clock(Clock)` installs the tree's clock; `frame(w, h, measurer)` runs one frame; `windowFloor()` | H; `frame` is also how a test runs frames headless |

## How the boundary is held

| What | Held by |
| --- | --- |
| Nothing on `Gui` returns `Seams` or takes a function the GUI keeps | vexelray-gui's `GuiSurfaceTest` (`nothingOnGuiLeadsToTheSeams`) |
| No framework type an application is handed takes or returns a function | vexelray-framework's `ApiWalkTest` (T2.5, T7.2) |
| An application's `@Draw` is a pure, allocation-free asked half | vexelray-framework's processor (`DrawCheckTest`) |
| A `@View` that names `spi` is refused, imported or spelt out | vexelray-framework's processor (`SpiBoundary`; `Seams.of(gui)` is public, so this is what keeps a view off it) |
| This page's table names every public method of `Seams` | `SeamsMapTest` |
| A widget's handlers and asked half keep the rules | the widget library's own tests |

## A widget's asked half

A widget whose picture moves (a feed that plays, a scroll that glides) answers each frame through an `Asked`, as an
application's `@Draw` does, and registers it itself:

```java
Seams.of(gui).ask(this::draw);         // once, when the widget is made; unask only if it is let go for good

long draw(Frame frame, Drawn out) {     // GUI thread, every frame the tree runs
    Feed f = playing;                    // volatile, or immutable: no lock, no allocation, no I/O, no logging
    if (f == null) {
        return Frame.NEVER;              // at rest: the loop parks
    }
    int slot = f.due(frame);
    if (slot >= 0) {
        out.show(f, slot);
    }
    return f.next(frame);                // when this needs asking again
}
```

- **Register once, answer `NEVER` at rest.** A part asked on a frame with nothing to change costs a call; one that
  answers `NEVER` costs no frame. Registering and unregistering per picture is not needed, and leaves what it last set
  set (`unask`).
- **It reads only what its told half wrote,** through a volatile field or an immutable value, never through a lock a
  caller on another thread might hold. A widget's methods are called from anywhere; its asked half must never wait on
  them.
- **Its rules are the processor's for `@Draw`**, held by the widget's tests rather than at compile time.
- **Testing it:** build the widget on a headless `Gui`, put it in `gui.root()`, and run frames with
  `Seams.of(gui).frame(w, h, measurer)`; the real `Drawn` chooses, as a window's would. vexelray-codec's
  `VideoViewTest` does this, and settles a feed's shown slot (`Feed.select`) as the renderer would.
- **A node drawn at nothing may never hear a feed's hand-over** (Rasterware FN-32), so a widget waiting on a first
  frame looks again a frame on rather than answering `NEVER`.

## Before asking for a new door

The GUI thread has one exception to "no code runs there": a part's declared halves (T7.6). For a widget, every half it
needs is a seam above. A need that none of them meets is a finding: write it in the application's
`framework-notes.md`, name the seam that came closest, and say what it would take; do not add a second channel beside
this one.
