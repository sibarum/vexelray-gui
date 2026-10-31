# Rich text: effect spans, a formatted clipboard, a WYSIWYG field and glyph morphs

**Status: proposed 2026-10-30, not started.** One build, several features. `TextField` already is the component:
a `Document` (text, caret, anchor, spans, last edit) as one value, spans that remap through every edit, real faces
per character, and a read-only mode that keeps selection and copy. The canvas already has the effects: shadelets
that reshape and paint a distance, `@PerInstance` values for what animates, and `Effects.FIELD_MORPH`. What is
missing is the wiring between them, and a clipboard that carries more than a string.

What comes out of it:

| Feature | Steps |
|---|---|
| Selectable, copyable formatted text | shipped as `TextField.readOnly(true)`; step 0 fixes the reference |
| Copy with or without formatting | 3 |
| Text drawn by any look: outline, glow, shadow, an application's own shadelet | 1 |
| Animating a parameter of that look | 2 |
| A WYSIWYG field (`readOnly(false)`, with formatting commands) | 4 |
| Glyphs morphing between faces | 5 |

The measurement that makes each step checkable is a capture: `LookCaptureTest` already compares a look's pixels, and
every step below ends in one.

---

## 0. What exists, and one stale sentence

- **Selection without editing is shipped.** `TextField.readOnly(true)` closes the user's edit channel and keeps the
  caret, selection, Ctrl+C and every motion key. [keyboard-focus-text.md](../reference/keyboard-focus-text.md) §4.1
  and §8 still list "non-editable selection" as not done. Fix both in the first commit of this plan.
- **Spans** are a fixed record: `fg`, `bg`, `underline`, `weight`, `slope`. Weight and slope are part of measurement;
  colour and underline are a draw.
- **Span changes are not undoable.** `Edit.SetSpans` produces a document with `lastEdit == null`, and
  `TextField.apply` records history only from a `TextEdit`; anything else is a barrier. Correct for a syntax
  highlighter rewriting its spans, wrong for Ctrl+B.
- **Glyphs never draw with a look.** `Canvas.text` → `appendGlyphs` → `glyphVert` writes every quad with the core
  text look. `Canvas.box` and `Canvas.field` take a `Look` and per-instance floats; text has no such overload.
  `TreeRenderer.drawLineText` splits a line into runs of one face and one colour, and draws each with `canvas.text`.
- **The clipboard is a string.** `TextClipboard` is `get()`/`set(String)`; tactroller's Windows backend moves
  `CF_UNICODETEXT` and nothing else.
- **An application already composes the shader.** `GuiApp` takes a `CanvasShader.Composed` built from
  `CoreShadelets.composition(type).with(...)`, so an application's own shadelet is already a supported thing. It
  has nowhere to be used on text.

## 1. Effect spans

A span may name an **effect**: a `Look` whose source is the text source, so its modifiers and painters act on the
glyph's distance exactly as they act on a box's or a field's. Every effect in `vexelray-shadelets` that is a
modifier or a painter works on a glyph unchanged, and so does an application's own.

**Engine (`vexelray-canvas`).** `Canvas.text(..., Look look, float... instance)`: `appendGlyphs` sets the element
from the look the way `box` does, and grows each glyph quad by the look's reach. A look whose source is not the
composition's text source (`CoreShadelets.text(type)`) is refused loudly, once: a glyph quad has no box to give a
box source.

**The reach is the bake's.** A glyph's distance is only true out to its atlas's `distanceRange`. An outline or glow
wider than that reads a clamped field and comes out as a flat-edged slab. So the effect's reach is clamped to the
face's range, and the text says so once. A family meant to glow is baked with a wider range in
vexelray-msdf-maven-plugin's `<families>`. That is a build setting, not a runtime fallback.

**GUI (`vexelray-gui-core`).** `Span` gains one component, `effect`: a key, not a `Look`. The key resolves through
the node to a `Look` and the instance floats. Keeping the look out of `Span` keeps a `Document` a plain value that
compares cheaply and holds nothing GPU-side.

```java
field.effect("hot", look);                    // the node knows what "hot" draws with
field.spans(List.of(Span.effect(4, 9, "hot")));
```

`drawLineText` splits runs on face, colour **and** effect, and draws an effect run with the new overload. An effect
is a draw, never measurement: like a colour span, changing one never relays out. That holds even for `WEIGHT`, which
moves the edge outward. A real weight change is a face change and does relay out (font-families.md §3.3); a shader
weight is how it looks. The two are different knobs, and the docs say which is which.

Done when: a capture of a field with an outlined span and a glowing span matches a reference, and a capture of the
same field with the spans' effects swapped differs only inside the spans' quads.

## 2. Animating an effect

**The instance values are not in the `Document`.** If they were, every frame of an animation would be a new
document version, a span remap, and a candidate for history. They live on the node, per effect key:

```java
field.effectValues("hot", 0.4f);              // a write, as a field's instance is (ImageField's javadoc)
```

That is a draw-only prop write, which costs what `ImageField`'s per-frame instance costs today: nothing beyond the
write. Shadelets read no clock (`Effects`' class comment), so the values come from the application's timeline: a
`Ramp` writes them. A frame stays a function of what was drawn, so captures are reproducible and an idle loop sleeps.

If [frame-contract.md](frame-contract.md) lands first, an effect ramp is a `PaintMotion` like any other, and this
step uses that rather than a `KronoGui` loop.

Done when: a capture at three points of a ramp matches three static captures with those values written by hand,
and a field whose ramp has finished wakes no frames.

## 3. A clipboard that carries formatting

**Tactroller.** `ClipboardBackend` gains a set of formats written in one open/close: plain text always, HTML when
given. On Windows that is `CF_UNICODETEXT` plus `RegisterClipboardFormat("HTML Format")` with its CF_HTML header
(byte offsets of the fragment in UTF-8). On X11 it is a second target, `text/html`. Reading asks for the richest
format present. macOS has no backend yet and stays plain.

**GUI.** `TextClipboard` gains a `Clip(String plain, String html)` alongside `get`/`set`. The existing methods stay
as the plain view of it, so every current caller is untouched. `TextField`'s copy writes both formats: Ctrl+C with
formatting and Ctrl+Shift+C without, by a claim the application can rebind.

**Writing is the framework's, reading is a seam.** Writing spans as HTML is unambiguous and belongs here, as
`Svg` does for pictures: `<b>`, `<i>`, `<u>`, and colour and background as inline `style`, one element per run of
equal attributes. An effect is written as its key in a `data-` attribute and otherwise dropped, since no other
application can draw it. Reading HTML into spans is a parser, and the framework holds no opinion on markup
(typeset.md §2). So paste goes through a `ClipReader` the application installs. The default keeps only what spans
can hold (bold, italic, underline, colour, background) and drops everything else, rather than guessing.

Done when: copying a styled range and pasting it into a second field reproduces its spans; pasting into a plain-text
target (Notepad, a terminal) gives the plain text; and a paste from a browser keeps bold, italic and colour.

## 4. The WYSIWYG field

Editing is the existing `readOnly(false)` field. What it lacks is formatting as edits.

- **Span changes become history.** A formatting edit produces a `SpanEdit` (the spans before, the spans after, over
  the selection), and `Document` carries it beside `lastEdit`. `TextField.apply` records it as its own `Change`.
  `SetSpans` stays unrecorded: a highlighter's rewrite is still not the user's act. The distinction is the edit's,
  not the field's: an edit says whether it is the user's.
- **What typed text looks like.** Typing at the edge of a span must have a rule, and today it is whatever
  `Span.remap` does with an insert at `end`. The field keeps **typing attributes**: taken from the character left of
  the caret when the caret moves, changed by a formatting command with no selection, applied to the next insert.
  The rule is the same as every word processor's, so nobody has to learn it.
- **Commands.** `TextField.toggle(Attribute)` over the selection, or over the typing attributes when there is no
  selection, claimed at `FOCUSED` as Ctrl+B / Ctrl+I / Ctrl+U. `formatAt(caret)` publishes the attributes in force,
  so a toolbar shows bold as pressed without reading the spans itself.

**Scope cut: one size per field.** Mixed sizes within a line (a heading, a large word mid-sentence) break the
one-dimensional line model the field is fast because of. Line height becomes per-line, measured from its largest
face; caret geometry, Up/Down and the read-model all assume one size. That is its own plan. So are paragraph
attributes (alignment, lists, headings as blocks) and inline embeds.

Done when: a scene types a sentence, bolds a word, undoes, redoes, and copies. `docs/scenes/` drives it through
ottermate, and the capture after each step matches.

## 5. Glyph morphs

`Effects.FIELD_MORPH` mixes two cells of one texture by `t`. Glyphs need three things it does not do:

- **The text source.** It mixes `CoreShadelets.FIELD` distances; a glyph is an MSDF median. A `GLYPH_MORPH` source
  takes the median at both cells, then mixes the two distances. Mixing after the median is what keeps the corners.
- **A cell transform, not an offset.** Two glyphs' cells differ in size and bearing, so the second cell is an offset
  *and* a scale from the first (four per-instance floats, not two), and the quad covers the union of both boxes. The
  reach overload that takes instance values already exists for this ("a morph to a larger box reaches as far as the
  larger box").
- **One texture.** Every face is its own atlas, and a morph reads both cells from what is bound. Either the plugin
  bakes a family's faces into **one** atlas (pages of one texture), or the source samples a second texture. The first
  needs no new binding and keeps a family's runs together. It is the recommendation, and it is a plugin change.

**Advances stay put in v1.** The layout uses the first face, and the morph happens inside each glyph's slot, so a
morph is a draw and never a relayout. Interpolating the advances too means laying the line out every frame. That is
possible, but it is a measurement change and comes after.

**What looks good.** Faces of one family (regular → bold, upright → italic, along a variable font's baked
instances) morph cleanly, because the shapes correspond. Different letters crossfade through blobs. The demo shows
the first, and the docs say the second plainly rather than promising it.

Done when: a field morphing a word regular → bold matches captures at `t = 0` and `t = 1` against the static faces,
and at `t = 0.5` has no pixel outside the union of the two.

---

## 6. Order, and what each step needs

| Step | Repos | Size | Needs |
|---|---|---|---|
| 0 | vexelray-gui (docs) | trivial | — |
| 1 | vexelray (canvas), vexelray-gui | medium | — |
| 2 | vexelray-gui | small | 1 |
| 3 | tactroller, vexelray-gui | medium | — |
| 4 | vexelray-gui | medium | 3 for paste with formatting; otherwise none |
| 5 | vexelray (msdf plugin, shadelets), vexelray-gui | medium–large | 1 |

1 and 3 are independent and can be built at the same time. 1 gives the most for the least: a moderate change in the
GUI unlocks shader work that is already built.

## 7. Interactions

- **[runtime-themes.md](runtime-themes.md) R3** moves span colours onto paints. An effect span's colours (a glow's
  colour, an outline's) are instance values today. They should index the same paint table once R3 lands, or a theme
  swap leaves them stale, which is the stale-pixel count that plan is driving to zero.
- **Typeset selection** ([todo.md](todo.md), "Selection. Insured, not built") would reuse step 3's HTML writer and
  clipboard, but not the field's selection model: its runs are two-dimensional. Out of scope here.
- **Selection across nodes** (dragging over several labels the way a web page allows) is a different feature: a
  selection over the tree, not over one document. Out of scope, and not implied by anything above.
