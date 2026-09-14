# The semantic read-model

Status: **landed**. `SemanticSnapshot` is a published, versioned read-model describing what every node in the
tree **is** — role, name, structure and interaction state — beside `LayoutSnapshot`, which describes where it
is. Same node ids, same version, same frame.

---

## 1. Why it exists

`NodeKind` has two values: `BOX` and `TEXT`. That is the correct vocabulary for a renderer — it says how a node
lays out and how it draws, which is everything drawing needs to know — and it is an anonymous tree to everyone
else.

Consider a button. It is a text node with a background, a corner radius, a border and a click handler. Nothing
published anywhere said "button", because nothing that draws it needed to know. So a reader that is not the
renderer could see a box at `(412, 88)` containing the string `"Save"`, and could not distinguish it from a
heading, a disabled label, or a table cell that happens to say the same thing.

Three readers need better than that, and none of them is hypothetical:

- **An automation agent** (docs/automation.md) driving the real app for troubleshooting. It must click "the Save
  button", not "the box at 412,88" — coordinates are exactly the thing that goes stale between the frame it read
  and the frame it clicked in.
- **A remote thin client** (C4 in docs/architecture-proof-plan.md, M5). A peer with no atlas already has glyph
  positions baked into `TextMetrics`, so in principle it can draw. It cannot navigate, focus, or announce
  anything without knowing what the boxes are.
- **A screen reader**, eventually. The same question, asked by a different consumer.

The alternative — each of those reaching into `RetainedNode` for what it needs — is the seam resolved decision 7
closed. `RetainedNode` is GUI-thread state, not a published snapshot; reading it from outside is how you get
behaviour that differs on screen and off it, silently, until somebody looks.

## 2. Shape

```java
public record SemanticSnapshot(long version, long rootId, Map<Long, SemanticNode> nodes)
```

Pure data, keyed by node id, transport-serializable: no node references, no unresolved lengths, nothing that
needs a layout context to interpret. `node(long)` returns `SemanticNode.ABSENT` rather than null for a node the
snapshot does not describe, and `root()` gives a reader somewhere to start.

`SemanticNode` carries:

| Field | Meaning |
|---|---|
| `id`, `parentId`, `children` | Structure. Children in tree order, which is also paint and traversal order. |
| `kind` | `BOX` or `TEXT` — how it draws, kept because it is sometimes the answer. |
| `role` | What it *is*, as declared by the widget that built it. `""` if undeclared. |
| `name` | What a person would call it — see §4. |
| `visible` | Whether it is in the laid-out tree this frame. |
| `hitInert`, `floating` | Pointer-transparent; placed by `floatAt` rather than by flow. |
| `focusable`, `focused` | Whether it can take keyboard focus, and whether it holds it. |
| `editable`, `text`, `caret`, `selectStart`, `selectEnd` | The text node's own state. |

Read it as a coalesced `State` via `Gui.semantics()`, or take the latest lock-free via `Gui.semanticSnapshot()`
— the same two-door pattern `Gui.layout()` / `Gui.layoutSnapshot()` offers.

## 3. The version is the contract

`SemanticSnapshot.version` and `LayoutSnapshot.version` are **the same counter**, and both snapshots are built
from one walk of one tree inside `publishLayout`.

This is the load-bearing property, and it is why they are published together rather than by two independent
producers. A consumer asks the semantic snapshot *which* node is the Save button and the layout snapshot *where*
it is. If those two answers can ever come from different frames, it clicks where the button used to be — and
does so intermittently, under load, in a way that looks like a bug in whatever it was trying to test.

So: join by `id`, and check that the versions match if you held either across a frame.

They are two snapshots rather than one wider `NodeLayout` because they change at very different rates. Geometry
republishes whenever a box moves, which is most frames of an animation; what a node *is* changes rarely. A
consumer of one usually does not want the other at that rate. Sharing the version is what keeps them joinable
in spite of that.

## 4. Role and name

**Role is declared, never inferred.** A widget says what it built:

```java
Node b = gui.text(label).role("button");
```

It is a free-form lower-case string, not an enum, because the set is open by construction: an application
composes widgets this framework has never heard of and must be able to say what they are without editing a core
enum. **Core branches on the value nowhere** — it stores it, publishes it, and is otherwise indifferent — so an
unrecognised role costs nothing and no consumer's vocabulary is privileged.

`Node.role` affects nothing that is drawn, laid out, or hit-tested.

**Name is derived**, because a widget's label is usually a child rather than its own text: asking a button's box
for its text gets nothing, while a person reads the label inside it. So the name of a node with no text of its
own is the text of its descendants, joined in tree order — bounded twice:

1. **Stop at the next declared role.** A toolbar's name is not every button on it; a tab strip's name is not its
   tabs. Those are structure, addressable in their own right.
2. **Stop at two levels down.** Roles alone are not enough: the *content* a container holds is ordinary
   application boxes that declare nothing, so a `tabs` node would otherwise be named after the full text of
   every page inside it. A widget's own label is one or two levels down. Deeper than that, it belongs to
   something else — whether or not that something has said so yet.

A name identifies a node among its siblings. It was never meant to be a transcript of what the node contains; a
reader that wants the text of a subtree walks the structure for it, which is a different question the snapshot
already answers.

## 5. Roles in the tree today

Declared by `-widget`:

| Role | Node |
|---|---|
| `textfield` | `TextField`'s editable node |
| `slider` | `Slider`'s track |
| `tabs` / `tab` | `Tabs`' root / each header |
| `tree` / `treeitem` | `TreeView`'s root / each row |
| `list` / `listitem` | `ListView`'s root / each row — the words are an argument, see below |
| `table` | `Table`'s root |
| `row` | its header, and (via `ListView.roles`) each body row |
| `columnheader` / `columngrip` | one header cell / the boundary a drag resizes it by |
| `rowgroup` | the `ListView` a `Table` uses as its body |
| `menu` / `menuitem` | `ContextMenu`'s column / each row |
| `tooltip`, `titlebar`, `popout` | those widgets' roots |
| `colorpicker` | `ColorPicker`'s panel |
| `colorpicker.field` / `.hue` / `.alpha` | its three draggable ramps |
| `colorpicker.preview` / `.recents` / `.swatch` | the sample, the recents strip, each slot in it |
| `switch` | `Toggle`'s track |
| `segment` / `segment-option` | `Segment`'s bar / each cell |
| `swatches` / `swatch` | a `Property.swatches` column / each colour-map card in it |
| `inspector`, `inspector-section`, `inspector-row` | `Inspector`'s panel, a section heading, one labelled row |
| `inspector-card` / `inspector-card-head` | a folding card / the heading that folds it |
| `rail`, `rail-item`, `rail-action` | `Rail`'s icon column, a page icon, a non-page action |
| `rail-panel` / `rail-panel-head` | the panel beside it / its heading |

**`ListView` and `Table` declare their parts now**, which was the easy half of the gap this section used to
describe. Two decisions inside it are worth keeping:

- **The row role goes on what the builder built, not on the box `ListView` wraps it in.** Both are "the row" —
  one carries the click and the selected look, the other the contents — and declaring both makes every row two
  rows in the tree. The contents win because a name is derived from the text *below* a node (§4, depth 2) and
  the wrapper is one level too far up: declared there, every row in a table reads `row ""`.
- **The words are an argument** (`ListView.roles(container, item)`), because a `Table`'s body is one of these
  and its rows are rows, not list items. The alternative was the composing widget declaring a second role over
  the top of the first, which is two nodes claiming to be the same row.

**The hard half is still open.** A virtualised body has no node for a row that is off screen, so the honest
description of a hundred-thousand-row list is not the thirty rows that happen to exist. That wants a role able
to carry a count and a realized range — the same shape of problem as §6's hidden nodes, and for the same reason:
"no such node" is not an answer. Until then a reader sees the rows in the window and no statement about the
rest. Tracked in `todo.md` §4.9.

Declared by the demo (`Ui`): `button`, `toggle`. **There is no `Button` widget in the framework**, deliberately
— `Ui` says why — so applications compose their own and declare the role themselves. That is the seam working as
intended rather than a gap: `Node.role` is how an application says what it built. The demo's `toggle` and the
framework's `switch` are two different things wearing similar words: the demo paints a text node that flips, and
`Toggle` is the widget under **Widgets** in the README. A reader should not have to know which one it is looking
at, and a later pass should reconcile the two words. Tracked in `todo.md` §4.9.

## 6. Two things that will bite

**A hidden node is described, not omitted.** "It exists and you cannot see it" is an answer; "no such node" is
not, and a reader asking why it cannot find the Save button deserves the first one. So hidden subtrees appear in
the snapshot with `visible == false`.

**Do not infer visibility from geometry.** A hidden node is *also* present in `LayoutSnapshot`, carrying
whatever rect it last had — only its derived text metrics are cleared. Finding a rect therefore proves nothing
about whether a node can be seen or clicked. Read `SemanticNode.visible()`. (`SemanticSnapshotTest` pins this
asymmetry rather than assuming it away; whether the geometry snapshot *should* publish hidden rects is an open
question, and a separate one.)

**Visible is not the same as reachable, and the other one is geometry.** `visible` says a node is in the
laid-out tree; it says nothing about a row scrolled out of its list, which is in the tree, has a rect, and is
somewhere nobody can point at. That question is answered by `NodeLayout.visibleRect` — the part of a node's box
that survives its ancestors' clips (`clipped()`, `clippedAway()`) — because clipping is geometry and belongs
where the geometry is. A reader deciding whether it can click something has to ask both: `visible()` for "is it
in the tree at all", `visibleRect` for "and is any of it on screen".

## 7. Testing

`SemanticSnapshotTest` in `-widget`. These are compliance conditions, not features, in the same sense as
`LabelGeometryTest`: the snapshot exists so that something which is not the renderer can use it, and every claim
that makes it usable is checkable today, with no such consumer present. The ones that are not checked are the
ones that quietly stop being true.
