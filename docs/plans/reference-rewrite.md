# The locator: updating source references to the moved documents

Status: **done.** All 99 source references were rewritten on 2026-09-14, in the order below. Verified three
ways: nothing in the tree still names the flat layout, every rewritten path resolves to a file, and all 28
distinct `§N` citations resolve to a real heading in the document they now name. Kept as the record of what
moved where, which is the thing a reader of an old commit or an old branch will want.

On 2026-09-14 every document under `docs/` moved into one of three folders — `reference/`, `guides/`,
`plans/` — and four were renamed. Markdown was updated in the same pass; **the source tree was deliberately
not**, so that the javadoc and comment edits could be made deliberately rather than by blind find-and-replace.

This is the record they worked from.

## The one thing that makes this safe

**No document was merged, and no section was renumbered.** That was the deciding constraint: javadoc cites
these documents *by section* — `docs/automation.md §3`, `docs/layout-read-model.md §2.1` — so fusing two
documents would have converted a mechanical path rewrite into a semantic one, where every `§N` has to be
re-derived and hand-checked against the merged numbering. The cohesion is carried by
[docs/README.md](../README.md) and by the folders instead, which costs nothing and breaks nothing.

So for every reference below, **the section number after the path is still correct**. Only the path changes.

## Old path → new path

| Old | New | Java refs | Files |
| --- | --- | ---: | ---: |
| `docs/architecture.md` | `docs/reference/architecture.md` | 1 | 1 |
| `docs/layout-read-model.md` | `docs/reference/layout-read-model.md` | 27 | 16 |
| `docs/semantic-read-model.md` | `docs/reference/semantic-read-model.md` | 2 | 2 |
| `docs/keyboard-focus-text.md` | `docs/reference/keyboard-focus-text.md` | 0 | 0 |
| `docs/navigation.md` | `docs/reference/navigation.md` | 1 | 1 |
| `docs/transfer.md` | `docs/reference/transfer.md` | 0 | 0 |
| `docs/drawing.md` | `docs/reference/drawing.md` | 1 | 1 |
| `docs/typeset.md` | `docs/reference/typeset.md` | 30 | 14 |
| `docs/reliable-plotting.md` | `docs/reference/reliable-plotting.md` | 1 | 1 |
| `docs/reactive-timing.md` | `docs/reference/reactive-timing.md` | 0 | 0 |
| `docs/automation.md` | `docs/reference/automation.md` | 24 | 19 |
| `docs/automation-cli.md` | `docs/reference/automation-cli.md` | 5 | 3 |
| `docs/ottermate.md` | `docs/guides/ottermate.md` | 0 | 0 |
| `docs/todo.md` | `docs/plans/todo.md` | 5 | 4 |
| `docs/gui-decomposition.md` | `docs/plans/gui-decomposition.md` | 0 | 0 |
| **`docs/loudness-plan.md`** | **`docs/plans/loudness.md`** *(renamed)* | 0 | 0 |
| **`docs/architecture-proof-plan.md`** | **`docs/plans/architecture-proof.md`** *(renamed)* | 2 | 2 |

99 references in the source tree. The two renames drop a `-plan` suffix that the folder now says.

## Which files carry them

Ordered by how much of the work each document is. Nine documents have no source references at all and need
no visit.

### `typeset.md` → `reference/typeset.md` — 30 refs in 14 files

`-typeset`: `Arrangement`, `Box`, `package-info`, `Profile`, `Recipes`, `ToneMap`, `Typeset`, `TypesetBlock`,
and tests `GeometryTest`, `ProjectionTest`, `ToneMapTest`, `VocabularyTest`.
Also `-core/Gui.java` and `-architecture/DispatchGuardTest.java`.

### `layout-read-model.md` → `reference/layout-read-model.md` — 27 refs in 16 files

`-core`: `app/TreeRenderer`, `Gui`, `layout/Clip`, `layout/LayoutSnapshot`, `layout/NodeLayout`,
`layout/TextMeasurer`, `LayoutReader`, `model/PropKey`, `model/Reconciler`, `model/RetainedNode`, `Node`,
`text/TextMetrics`, and `test/LayoutReadModelTest`.
`-widget`: `TextField`, and tests `CaretScrollTest`, `MultilineTest`.

### `automation.md` → `reference/automation.md` — 24 refs in 19 files

`-automation`: `Cursor`, `package-info`, and tests `AutomationTest`, `HoverPathDiagnosisTest`.
`-automation-cli`: `package-info`.
`-core`: `app/GuiApp`, `app/GuiWindow`, `app/WindowSpec`, `Gui`, `input/InputDispatcher`,
`layout/SemanticSnapshot`, `model/PropKey`, `model/SemanticNode`, `Node`, `WindowControls`,
`WindowInstrument`.
`-widget`: `TitleBar`, and tests `InstrumentStripTest`, `SemanticSnapshotTest`.

### `automation-cli.md` → `reference/automation-cli.md` — 5 refs in 3 files

`-automation-cli`: `AutomationClient`, `Launch`, `package-info`.

### `todo.md` → `plans/todo.md` — 5 refs in 4 files

`-architecture/Sealing`, `-typeset/TypesetBlock`, `-widget/package-info`, `-widget/Table`.

### `semantic-read-model.md` → `reference/semantic-read-model.md` — 2 refs in 2 files

`-automation/Automation`, `-automation/AutomationTest`.

### `architecture-proof-plan.md` → `plans/architecture-proof.md` — 2 refs in 2 files

`-architecture/LayeringGuardTest`, `-architecture/ModelWriterGuardTest`. **Renamed as well as moved.**

### One reference each

| Document | File |
| --- | --- |
| `architecture.md` | `-core/package-info.java` |
| `navigation.md` | `-core/Gui.java` |
| `drawing.md` | `-draw/package-info.java` |
| `reliable-plotting.md` | `-plot/package-info.java` |

## Doing it

Per document, not globally — `docs/automation.md` is a prefix of nothing, but `docs/architecture.md` and
`docs/architecture-proof-plan.md` are close enough that an unanchored pattern will bite. Anchor on the `.md`:

```bash
grep -rl --include=*.java 'docs/typeset\.md' . | grep -v /target/ \
  | xargs sed -i 's|docs/typeset\.md|docs/reference/typeset.md|g'
```

Do the two renamed documents **before** their unrenamed prefixes, or not at all in the same pass:

```bash
grep -rl --include=*.java 'docs/architecture-proof-plan\.md' . | grep -v /target/ \
  | xargs sed -i 's|docs/architecture-proof-plan\.md|docs/plans/architecture-proof.md|g'
```

Then verify nothing is left pointing at the old flat layout:

```bash
grep -rn --include=*.java -o 'docs/[a-z0-9-]*\.md' . | grep -v /target/
```

That should print nothing. Every surviving reference should read `docs/reference/`, `docs/guides/` or
`docs/plans/`.

## One thing to decide while in here

`reference/automation.md` links to `calculator-vexel-demo/docs/driving-the-preview.md`, which does not exist —
that sibling has `design.md`, `framework-notes.md`, `plot-viewport.html` and `retrospective.md`. The link was
already broken before the restructure; the move only corrected its depth. Either the document is still owed by
that repo, or the link should point at whichever of those four absorbed it.
