# Decomposing Gui

`Gui` is the composition root, the frame loop, the tree factory, the mutation publisher, the zoom/DPI/viewport
authority, the layout read-model publisher, the text geometry solver, the scroll-into-view solver, the
resize-observer registry, the navigation engine, and a pass-through facade over `InputDispatcher`. Every new
seam has landed on it, including the one added most recently.

This is the plan to stop that. **Nothing in §2 has been extracted yet**, and the measurement is the reason to
say so plainly rather than leave the plan sitting there reading as though it were underway:

| | when this was written | now |
|---|---|---|
| lines | 1,929 | 2,193 |
| public methods | 86 | 91 |
| fields | ~60 | ~68 |

Five more methods and 264 more lines since 2026-08-30, which is what "every new seam lands here" looks like when
it is left alone. The most recent 33 of those lines are `wakeForInput` and the derived-kind change, and both
were the right change to make — that is the point. Nothing on this list is being *caused* by bad decisions
downstream; the class is simply the place a good decision has nowhere else to go. The plan is not wrong, it has
not been started, and the cost of not starting is now written down.

---

## 1. What the end state is, precisely

**The god object is state + logic in one class. It is not the facade.** A class with 86 methods that each
delegate one line is an index, not a god object — it holds nothing, decides nothing, and cannot be the reason two
features collide. So the target is not "fewer methods on `Gui`". It is:

- `Gui` keeps: the composition root, `frame()`, the wake discipline, `close()`. Target **under 400 lines**.
- Every other concern is a component that owns its own state, is constructible and testable alone, and is under
  **300 lines**.
- **Components communicate by value, never by calling back into `Gui`.** `Metrics` produces a `LayoutContext`;
  `LayoutPublisher` consumes a laid-out tree and produces a `LayoutSnapshot`. Neither knows the other exists.
- `frame()` becomes what the docs already describe it as: a fixed pipeline, readable in one screen —
  dispatch → drain → navigate → layout → reveal → compute → motion → publish.

If, at the end, `Gui` still exposes `onClick` as a one-line delegate, that is a success and not a failure.
Deprecating delegates is a separate, later, optional decision (§5).

## 2. The components

Grouped by what they own, with the current members that move into each.

| Component | Package | Owns | Moves in | Approx. lines |
|---|---|---|---|---|
| **`TextGeometry`** | `core.text` | nothing (pure) | `resolveTextGeometry`, `resolveTextScroll`, `lineIndexOf`, `clamp` | ~180 |
| **`Scrolling`** | `core.layout` | nothing (pure) | `reveal(RetainedNode)`, `scrollDelta`, `scrollBy` | ~65 |
| **`Metrics`** | `core.layout` | zoom, DPI, viewport States; min size; resize border | `zoom*`, `dpi*`, `viewport()`, `minSize`, `resizeBorder*`, `rootEmPx`, `lastZoom/lastDpi/lastViewport*/lastLayout*` | ~200 |
| **`LayoutPublisher`** | `core.layout` | `latestLayout`, `layoutVersion`, resize watches | `layout()`, `layoutSnapshot()`, `onResize*`, `watchResize`, `publishLayout`, `deliverResizes`, `collectLayout`, `ResizeWatch`, `resolveGeometry` | ~200 |
| **`Navigator`** | `core.nav` | landmarks, revealers, walks, `windowKey` | `landmark*`, `reveals`, `navigate`, `windowKey*`, `accept`, `Walk`, `stepNavigations`, `ancestorsOutermostFirst`, `concealed` | ~230 |
| **`Trees`** | `core` | id counter, `MutationSink`, batch thread-local | `root/box/row/column/text/create`, `batch`, `publishMutation`, `MUTATIONS` | ~150 |
| **`Transfers`** | `core.drop` | held transfer, drop history | `onDrop`, `dropTargetsAt`, `onDragSource`, `dropHistory`, `transfer`, `dragSession`, `drag()`, `publishDrag` | ~120 |

Two things deliberately do **not** move:

- **Input.** `InputDispatcher` already owns all of it; `Gui`'s ~30 input methods hold no state and make no
  decisions. Moving them achieves nothing. What matters is that they stop *growing* — see §5.
- **`theme` / `clipboard` / `handlers` / `motion`.** Four small volatile holders with two methods each. Extracting
  them would produce classes smaller than their own file headers.

## 3. Order of work, by risk

Ascending. Each step is one commit, and the suite (whole build, ~10s) is the safety net between them.

1. **`TextGeometry`** — pure functions of a `RetainedNode` and a `TextMeasurer`. No state to move, nothing to get
   wrong, ~180 lines out of `Gui` immediately. Also the most obviously misplaced code in the file: text metric
   solving next to `zoomIn()`.
2. **`Scrolling`** — same, ~65 lines. `Node.scrollIntoView`'s whole implementation, findable by its name.
3. **`Metrics`** — first state move, but the state is three `State`s and five volatiles with no cross-talk. The
   test is that `frame()` asks it for one `LayoutContext` and one clamped canvas size, and nothing else reads
   zoom or DPI directly.
4. **`LayoutPublisher`** — depends on step 1 and 3 being done, so the compute phase has somewhere to live and a
   context to run against. This is the step that makes `frame()` readable.
5. **`Navigator`** — self-contained and freshly written, so it moves cleanly. Held until here only because it is
   the least urgent; move it earlier if navigation is being extended.
6. **`Trees`** — touches the constructor and every node-creating call site indirectly. Do it once the file is
   already half its size, so the diff is legible.
7. **`Transfers`** — smallest payoff, do last or never.

**Stop after step 4 if the appetite runs out.** Steps 1–4 remove roughly 650 lines and the two concerns that make
`frame()` hard to read; 5–7 are tidying.

## 4. The mechanical rule for each step

Extract-and-delegate, never extract-and-rewrite:

1. Create the component with the moved fields and methods, unchanged.
2. `Gui` constructs it and keeps every existing public method as a **one-line delegate**.
3. Run the suite. It must be green with no test edits — if a test needed changing, behaviour moved, which is a
   different commit.
4. Then, separately, add the component's own unit test that constructs it *without* a `Gui`. This is the actual
   proof the extraction was real: a component that cannot be built alone was not extracted, it was relocated.

Step 4 is the one that will fail first, and where it fails it will name a hidden coupling worth fixing.

## 5. After: keeping it decomposed

The decomposition does not hold itself. Two rules:

- **A new seam is a method on a component, not on `Gui`.** A delegate is added only when a call site would
  otherwise be unreasonably verbose, and adding one is a decision, not a reflex. Navigation should have been
  `gui.nav().go(...)` from the start; it went onto `Gui` because everything else had.
- **Expose the components**: `gui.metrics()`, `gui.layoutModel()`, `gui.nav()`, `gui.trees()`. The existing
  delegates stay for the dozen genuinely ubiquitous calls (`box/row/column/text`, `onClick`, `focus`, `theme`)
  and the long tail is left to fade.

An architecture guard is possible here and, unlike the reveal guard, is honest: `Gui`'s bytecode may not exceed a
declared method or field count. A cap that must be edited to be exceeded turns "the god object grew again" from
something noticed at review into something noticed at build.
