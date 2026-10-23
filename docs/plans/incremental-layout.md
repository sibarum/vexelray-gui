# Incremental layout: lay out what changed, not the tree

Status: **proposed 2026-10-23, not started.** What the code does today, the invariant the design rests on, six
steps, what each must not break, and how each is verified.

## What happens today

Verified in the code on 2026-10-23.

- **One dirty bit for the whole tree.** `Reconciler.layoutDirty` is a single boolean. Any prop whose
  `PropKey.affectsLayout(before, after)` is true, any `setText`, and every structural edit (`create`, `insert`,
  `arrange`, `remove`) set it. `Gui.frameBody` then calls `FlexLayout.layout` from the root: one label's new text
  re-measures and re-places every node in the window.
- **Scrolling is a relayout.** A wheel glide calls `requestLayout` on every frame it is in flight
  (`InputDispatcher.dispatch`), and so do a scrollbar drag, a page scroll on the track, `Reconciler.scrollTo`,
  `scrollToEdge`, and the second pass a reveal pays for in `Gui.frameBody`. A scroll offset changes no size; it
  moves one container's children.
- **Departure is a relayout.** `Gui.frameBody` calls `markLayoutDirty` when a node finishes leaving, though only
  its parent's flow changed.
- **Measurement repeats itself.** `FlexLayout.measure` recurses through the subtree and keeps nothing. `layoutBox`
  can call `place` three times per container (the first measure, the re-measure at the width a scrollbar leaves,
  the re-place at the scroll offset), each of which measures every child, whose own `layoutBox` measures its
  children again. Nested auto-sized containers make the cost grow with depth even on a pass where nothing changed.
- **Text is broken into lines repeatedly.** `TextFaces` caches nothing. `measureLines` stores `lineSpans` on the
  node, but `textBlockHeight` computes them again, and both are reached more than once per pass.
- **Everything after layout walks the whole tree.** `ReadModels.resolve`, `Displacement.settle`, `Clip.resolve`,
  `ReadModels.publish` and `NestedScroll.report` each visit every node whenever geometry changed.

**What already works and stays:** a write of the value a prop already has is dropped (frame-loop.md §B); a purely
visual prop redraws without a layout; a caret move sets `geometryDirty` only (layout-read-model.md §2.3).

## The invariant

**A node's layout is a pure function of its own props, its subtree, the box its parent gives it, and the
`LayoutContext`.** Everything in this plan follows from that:

- When none of those inputs changed, the node's previous result is still right, and its subtree need not be
  visited.
- When a node's subtree changed but its own measured size did not, its parent's arrangement cannot have changed:
  the work stops at that node.
- A scroll offset is not an input to any size, only to positions, so it never needs measuring.

Correctness is checkable, not argued: an incremental pass must produce the same rects as a full pass over the
same tree (§S6).

## Steps

### S1. Scrolling becomes a shift, not a layout

Overflow, the scrollbar reservation and the content extent depend on content size, not on the offset. A change of
offset therefore moves the scroller's flow children, and everything under them, by the difference — no measure,
no place. Rects are absolute, so this is one walk of that subtree, not of the tree.

- `scrollTo`, the wheel glide, scrollbar drag, page scroll and the reveal second pass take this path instead of
  `markLayoutDirty`.
- Floating children are anchored to the parent's frame, not its content, so they do not move (as today).
- **Scroll-lock stays a layout.** While attached, the offset is pinned to an edge measured from content height,
  so growth under a locked container still needs the layout pass — which it gets anyway, because growth is an
  edit.
- The compute phase still runs (`geometryDirty`): caret metrics, clips and the published snapshot moved.

Self-contained, and the largest win while scrolling: a glide currently lays out the whole tree for every frame it
is in flight.

### S2. A dirty bit per node

Replace the reconciler's global flag with two bits on `RetainedNode`: `needsLayout` on the node an edit touched,
and `childNeedsLayout` set on each of its ancestors up to the root. The global question "does this frame lay out"
becomes "is the root's either bit set".

- **An edit marks the node it changed and nothing else.** `setProp` and `setText` mark the node; `insert`,
  `arrange` and `remove` mark the parent (and, for a move, the old parent); departure marks the parent.
- `PropKey.affectsLayout(before, after)` stays the one place a prop says whether it affects size.
- **Inputs that are not a prop on the node**, each of which has to be covered:
  - `VISIBLE` changes the parent's flow, so it marks the parent as well as the node.
  - `FLOAT_X`/`FLOAT_Y`/`DOCK`: a floating node is placed against its parent's box, and a docked one moves the
    parent's viewport, so they mark the parent.
  - Percent, `FILL` and grow sizes depend on the parent's extent; they are handled by the box the parent passes
    down (§S4), not by marking.
  - Zoom, density and window size (`Metrics.Frame.moved()`) stay a full relayout.

S2 alone changes no costs; it is what S3 and S4 key off.

### S3. Measurements cached on the node

Store `measure(n, axis, knownWidth)` results on the node, keyed by axis, known width and the node's em, and clear
them when the node has either dirty bit. Because S2 marks every ancestor of a changed node, a cached measurement
that survives is correct by construction.

Text gets the same: line breaks and caret advances keyed by (text, `Styling`, wrap width, px), held on the node, so
`measureLines` and `textBlockHeight` share one result.

This makes **full** layouts cheaper too — the repeated measuring across `place`'s up-to-three passes becomes a
lookup — so it pays even before S4.

### S4. Stop where the size did not change

The layout walk carries, for each node, the box it was given last time. At a node:

- **Clean, with no dirty descendant, and given the same box and context:** return. Nothing below it is visited.
- **Same box and context, dirty below:** lay out its own subtree inside the box it already has; its siblings and
  parent are untouched.
- **Dirty itself:** re-measure; when the measured size along both axes equals the cached one, lay out its subtree
  in place and stop. When it differs, the parent's arrangement is dirty, and the walk re-places the parent's
  children — which is the case S2's marking of the ancestors already sent the walk to.

Fixed-size nodes, and nodes sized by their parent (`FILL`, grow, stretch), are natural stopping points: their size
does not depend on their content at all. A label's new text inside a fixed-width cell lays out that one leaf.

S2 and S4 belong together, and §S6's shadow comparison lands before them.

### S5. Narrow the passes after layout

Once layout is incremental, the whole-tree walks after it are the cost. The layout records the set of nodes whose
rect actually changed, and:

- `ReadModels.resolve` and `Clip.resolve` visit that set and its descendants.
- `Displacement.settle` reports moves from that set.
- `ReadModels.publish` reuses the previous snapshot's entries for nodes outside it.
- `NestedScroll.report` runs only for scrollers in the set.

Last, because until S1–S4 land, layout is the larger cost and these walks are not worth complicating.

### S6. Measured first, and the invariant tested

**Counters**, alongside the existing `flex layout` probe zone: nodes laid out per pass, `measure` calls, measure
cache hits, and text-cache hits. These go into `LoopCounters` so `ottermate` can read them
(flight-recorder.md) and the before and after of each step is a number, not a claim.

**Cost tests**, in the style of `DrawOnChangeTest`, stating the bound each step promises:

| change | nodes laid out |
|---|---|
| a wheel step, a scrollbar drag, `scrollTo` | 0 (S1) |
| a label's text in a fixed-width cell | 1 (S4) |
| a colour | 0 (today) |
| a row added to a list | the list and the new row (S4) |

**Shadow layout**, a test-only mode: after every incremental pass, run a full pass on a copy of the tree and
compare every node's rect, viewport, content extent and overflow. Driven over the demo chapters with random
sequences of edits, it catches any input S2's marking missed. It runs before S2 and S4 merge, and stays in the
test suite after.

## Order of work

1. **S6 counters and cost tests** — the baseline the rest is measured against.
2. **S1** — isolated, low risk, and the biggest win while scrolling.
3. **S3** — cheaper full layouts, and no new failure mode, because its invalidation is a superset of today's.
4. **S6 shadow layout**, then **S2 + S4** together.
5. **S5**, once the counters show the post-layout walks dominate.

## Not in scope

- Moving layout off the main thread. Vulkan, the window and present stay on main, and layout feeds them in the
  same frame.
- Positions relative to the parent instead of absolute. That would make S1 free rather than one subtree walk, but
  it changes what every reader of a rect sees. Worth revisiting only if S1's walk shows up in the counters.
