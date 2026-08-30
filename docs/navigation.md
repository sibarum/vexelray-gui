# Navigation and hyperlinks

Two features, one mechanism. **Landmarks** make a place in the UI addressable; **navigation** takes the user
there; **hyperlinks** are text that asks for it.

The users are the same three every time: an automated test that wants to be *at* a control rather than know how
to reach it, a macro replaying a sequence, and a link in a document.

---

## 1. The invariant

> A node is reachable if and only if every ancestor that can conceal it declares how to un-conceal it.

That single line is the whole design. Everything else follows from refusing the alternative, which is a navigator
that knows about tab panels, trees, drawers and wizards — an enumeration, and an enumeration is never finished:
the next container that can hide something is a change to a file that has nothing to do with it.

Inverted, the fact lives where it is true. `Tabs` knows that a hidden page is un-hidden by selecting its tab.
`TreeView` knows that a collapsed branch is opened by expanding it. Navigation knows neither, and is complete.

### What counts as concealment

Precisely: **the node exists, and this container is why it cannot be seen.**

| Situation | Declares a `Reveal`? | Why |
|---|---|---|
| Tab panel, page hidden | **yes** | the node is there and hidden by the panel |
| Tree branch, expanded then collapsed | **yes** | the rows exist, hidden in the branch's box |
| Tree branch never expanded | no | there are no nodes under it to name |
| Scrolling container | no | `scrollIntoView` already walks every scrolling ancestor |
| Tooltip, context menu, find bar | no | transient overlays; nothing in them is a destination |

---

## 2. Marking a place

```java
gui.landmark("prefs.theme.accent", swatch);
```

A name for a **node**, not for a route. Nothing in the address says which tab or how far down the page — the
route is derived, every time, from the tree as it stands. Move the swatch to a different tab and every link to it
still works.

Names are the application's to organise (dotted paths read and sort well; nothing parses them). A name may not
contain `/`, which separates the window from the landmark. Re-using a name rebinds it. The binding is released
with the node, like every other registration keyed by node id.

## 3. Going there

```java
Navigation nav = gui.navigate("prefs.theme.accent");
nav.arrival().thenAccept(node -> Cues.play(node, Cue.sweep()));   // say where the eye should go
```

The walk, one step per frame:

1. **Reveal**, outermost container first — each `Reveal` that acts costs a frame, because it changes what the
   next one is looking at.
2. **Scroll**, once every container has been asked and the node is genuinely visible.
3. **Focus**, and one frame later **report arrival** — so "arrived" means the user can see it, not that the last
   command has been issued.

It takes frames rather than returning done, and that is the point. Selecting a tab re-lays-out everything under
it; expanding a branch moves every row below it; scrolling to a node needs the node laid out where it finally is.
Done in one call, each step would act on the previous frame's geometry — which is exactly the bug that reads as
*"the link works the second time you click it."*

Unreachable is a **reported failure** (`Navigation.Unreachable`), never a silent arrival at something invisible:
an unknown name, a container that concealed the target and declared nothing, or a walk that ran out of frames
(240).

### Same events as a real user

Every step goes through the commands the widgets already expose — `tabs.select(i)`, `tree.expand(item)`,
`node.scrollIntoView()`, `gui.focus(node)`. These are the same methods a click ends up calling: `Tabs` registers
`gui.onClick(header, () -> select(...))`, so a click and a navigation converge one call in. A page arrived at
this way is in the state it would be in had the user clicked their way there, `onSelect` handlers and all.

Not synthesised pointer input, and the reason is structural: input needs a place on screen to aim at, and half of
what navigation does is *make the target have one*. A hidden page has no rect to click.

## 4. Across windows

An address is `window/landmark`, and it is a value:

```java
gui.navigate(Address.of("prefs", "theme.accent"));
bus.publish(NavTopics.GO, Address.parse("prefs/theme.accent"));   // identical, from anywhere
```

Two halves, deliberately split:

- **`GuiApp` opens windows.** It subscribes to `NavTopics.GO` and calls `show()` on the named window — a tree
  cannot make its own window exist, and a link into a closed preferences window has to open it.
- **`Gui` navigates itself.** Each tree accepts a request naming its own window (`gui.windowKey`) whose landmark
  it actually has, and walks it.

Neither half tells the other it is done. A window already showing is simply raised, which is safe because
`show()` is idempotent.

A bare landmark with no window means "whichever window this reaches" — which is what a link inside one window
usually means. If two windows on one bus own the same name, both answer; that is why addresses have windows.

---

## 5. Hyperlinks

A link is a range of text that stands for something else:

```java
field.links(List.of(new Link(8, 14, "prefs/theme.accent")));
```

- **Hold Ctrl (Command on macOS)** and every link underlines itself. Ctrl+click follows the one under the
  pointer; Ctrl+Enter follows the one under the caret.
- The default activation navigates, so the target is part 1's address and a word in a document reaches a control
  in another window. `onLinkActivate` replaces it for targets that are not places in this application — a URL, a
  file, an identifier in the application's own model. **Nothing here interprets a target beyond handing it on**,
  which is what keeps a document unable to make the framework act by containing the right string.

### A link is not a span

`Span` says how text is *drawn*; `Link` says what text *means*. Keeping them apart is what makes the behaviour
possible: the underline exists exactly while the modifier is held, so it is **derived** in `TextField.mirror()`
from the links and the keyboard together, and never committed into the document. A link that carried its own
underline could not stop being underlined.

Links remap through edits like spans do, so typing before one moves it and deleting its words removes it.

### Why a held key, and not hover

Nothing appears, moves or grows under the pointer as it passes over a link. A control that changes shape when the
pointer is merely near it moves the target out from under a click already on its way. A held key is something the
user *chose*, and it reveals every link at once rather than the one the pointer happened to find.

Ctrl+Enter exists for the same reason the underline shows all links at once: a link only a mouse can follow is
not a feature, it is a part of the document some users cannot reach.

### What this needed from the core

Two seams, both the same omission — device modifier state had no way out of the dispatcher:

- **`Gui.modifiers()`**, a `State<Set<Modifier>>`. A modifier press is not a command and is routed nowhere, so
  anything whose *appearance* depends on a held key had nowhere to read it. A modifier is a **condition**, which
  is why it is a `State` and not a topic. Cleared when the window loses focus — a key released over another
  window is never seen here, and a modifier held for ever is a UI stuck in a mode with no way out.
- **`ClickEvent.modifiers`**. Ctrl+click is a different command from a click, not a click with something to look
  up elsewhere — and "elsewhere" would be a second channel carrying keyboard state alongside a pointer event.

---

## 6. Enforcement

The invariant is checked at runtime, in the only place it can be seen to fail: a landmark that stays hidden after
every ancestor has been asked completes the navigation with `Unreachable`, naming the address. It does **not**
arrive at something invisible, and it does not scroll to a node nobody can see.

A static guard in `vexelray-gui-architecture` was considered and rejected: at the bytecode level, "conceals a
descendant" is indistinguishable from the many legitimate uses of `visible(false)` (chrome that toggles an icon,
tooltips, a checkbox mark), so the guard would be an exemption list, which is weaker than the runtime failure
above. If a future container makes the distinction visible in a type, the guard becomes worth writing.
