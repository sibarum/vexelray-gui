# Rich text: effect spans, a formatted clipboard, a WYSIWYG field and glyph morphs

**Status: proposed 2026-10-30, not started; revised the same day to put the drawing in the engine.** One build,
several features. `TextField` already is the component: a `Document` (text, caret, anchor, spans, last edit) as one
value, spans that remap through every edit, real faces per character, and a read-only mode that keeps selection and
copy. The canvas has the shadelets. The engine work this plan rests on (looks on glyphs and images, inflated glyph
quads, deformers in a composed vertex stage, a source over a run of glyphs, a glyph morph) is
**vexelray's `docs/canvas-looks.md`**. This plan is what the GUI does with it, plus the clipboard and editing.

What comes out of it:

| Feature | Steps | Engine (canvas-looks.md) |
|---|---|---|
| Selectable, copyable formatted text | shipped as `TextField.readOnly(true)` | — |
| Text drawn by any look: outline, glitter, bounce, an application's own shadelet | 1 | §1–§3 |
| Effects past the glyphs: bloom, smoke, decorations such as a slashed double red underline | 1 | §5 |
| Animating any of them, from a `Ramp` or from a song's beat | 2 | — |
| Copy with or without formatting | 3 | — |
| A WYSIWYG field (`readOnly(false)` with formatting commands) | 4 | — |
| Glyphs morphing between faces | 5 | §6 |

The measurement that makes each step checkable is a capture: `LookCaptureTest` already compares a look's pixels, and
every step below ends in one.

---

## 0. What exists

- **Selection without editing is shipped.** `TextField.readOnly(true)` closes the user's edit channel and keeps the
  caret, selection, Ctrl+C and every motion key. (The reference said otherwise until this plan's first commit.)
- **Spans** are a fixed record: `fg`, `bg`, `underline`, `weight`, `slope`. Weight and slope are part of measurement;
  colour and underline are a draw.
- **Span changes are not undoable.** `Edit.SetSpans` produces a document with `lastEdit == null`, and
  `TextField.apply` records history only from a `TextEdit`; anything else is a barrier. Correct for a syntax
  highlighter rewriting its spans, wrong for Ctrl+B.
- **Text is drawn in runs.** `TreeRenderer.drawLineText` splits a visual line into runs of one face and one colour
  and draws each with `canvas.text`, which has no look.
- **The clipboard is a string.** `TextClipboard` is `get()`/`set(String)`; tactroller's Windows backend moves
  `CF_UNICODETEXT` and nothing else.
- **An application already composes the shader.** `GuiApp` takes a `CanvasShader.Composed` built from
  `CoreShadelets.composition(type).with(...)`, and the supirvast plugin precompiles it. An effect library is a jar
  of shadelets; an application composes the ones it uses.

## 1. Effect spans

A span may name an **effect** by key. The node maps the key to what draws it:

```java
field.effect("hot", Effect.glyphs(bounce).span(bloom, Layer.UNDER));
field.effect("typo", Effect.span(slashedDoubleRed, Layer.OVER));
field.spans(List.of(Span.effect(4, 9, "hot"), Span.effect(12, 17, "typo")));
```

`Span` gains one component, `effect`, a key and not a `Look`: a `Document` stays a plain value that compares cheaply
and holds nothing GPU-side, and restyling an effect is a node write, not an edit.

**An effect has two placements, and may use both.**

- **Per glyph.** Each glyph of the span is drawn with the glyph look (canvas-looks.md §1). It can inflate its quad
  without resizing the glyph (§2), and move it with a deformer (§3): outline, glitter, a fill, a bounce or a spin per
  letter, a bend by inverse warp.
- **Per span.** One quad per span **per visual line** (a wrapped span is two rectangles), grown by its look's reach.
  Its source is either a **box** (decorations: underline, strike, slashes, squiggle, a highlight), placed from the
  face's underline metrics where today's underline is drawn, or **the span's text** (canvas-looks.md §5: bloom,
  smoke, anything that is the word's shape rather than a letter's). Its local coordinates are span space; a span
  split across faces or lines carries its offset into each piece, so a pattern does not restart.

**Layers.** A span quad is drawn `UNDER` the glyphs (glow, highlight, shadow) or `OVER` them (glitter, strike). A
glyph look either paints on top of the core glyph draw or **draws the ink itself**, in which case the renderer skips
the core draw for that run. Without that flag a gradient fill painted over the normal fill leaves a fringe of the
base colour at every anti-aliased edge.

**Renderer.** `drawLineText` splits runs on face, colour **and** effect, and draws an effect run with
`canvas.text(..., look, values)`; the span quads are emitted around them in layer order. An effect is a draw, never
measurement: changing one never relays out. That holds even for a deformer or a shader weight that moves the edge
outward. A real weight change is a face change and does relay out (font-families.md §3.3).

**Hit-testing is untouched.** Caret, selection and clicks read the read-model, which knows nothing of reach or
deformation. A bouncing letter is clicked where it rests.

Done when: a field with a glyph effect, a decoration and a bloom matches a reference capture; swapping the effects
differs only inside the effects' quads; and a click on a deformed glyph lands on its resting offset.

## 2. Animating an effect

**The values are not in the `Document`.** If they were, every frame of an animation would be a document version, a
span remap and a candidate for history. They live on the node, per effect key and per placement:

```java
field.effectValues("hot", glyphValues, spanValues);   // a write, as ImageField's instance is
```

A draw-only prop write, which costs what `ImageField`'s per-frame instance costs today. Shadelets read no clock, so
the values come from the application's timeline:

- **A `Ramp`** for a transition. If [frame-contract.md](frame-contract.md) lands first, an effect ramp is a
  `PaintMotion` like any other.
- **Kronometer for music.** A beat grid is `Rate.gridLine(n)`, a pure function of time, so it is *predictable* and
  pulses land on the beat rather than a frame late. Loudness is a live input from `Track.heard()` (up to the last
  quarter second of samples), and reacts after the fact, which suits "glow with the bass". **Anchor the grid to
  `Track.position()`**, the audio device's clock, not to wall time: the two drift over a song, and visuals timed on
  the wrong one slide off the beat. Whether a Kronometer `Tempo` can follow an external clock is unchecked, and a
  song that changes tempo needs kronometer's animated-tempo.md.

Done when: captures at three points of a ramp match three static captures with those values written by hand; a field
whose ramp has finished wakes no frames; and a demo pulses a word on the beat of a track with no drift after three
minutes.

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

- **Span changes become history.** A formatting edit produces a `SpanEdit` (the spans before and after, over the
  selection), and `Document` carries it beside `lastEdit`. `TextField.apply` records it as its own `Change`.
  `SetSpans` stays unrecorded: a highlighter's rewrite is still not the user's act. The distinction is the edit's,
  not the field's: an edit says whether it is the user's.
- **What typed text looks like.** Typing at the edge of a span must have a rule, and today it is whatever
  `Span.remap` does with an insert at `end`. The field keeps **typing attributes**: taken from the character left of
  the caret when the caret moves, changed by a formatting command with no selection, applied to the next insert.
  Effects are typing attributes too.
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

The engine supplies `GLYPH_MORPH` and a family baked into one atlas (canvas-looks.md §6). The GUI side:

- **A morph is an effect** whose glyph look is the morph source, with `t` as a value, so it animates like any other
  (§2).
- **The second face** is named on the effect (`Effect.morph(toWeight, toSlope)`) and resolved to the same family's
  face the way a span's weight and slope are, by `TextFaces`.
- **Advances stay put in v1.** The layout uses the first face and the morph happens inside each glyph's inflated
  quad, so a morph is a draw and never a relayout. Interpolating the advances means laying the line out every frame:
  possible, but a measurement change, and later.

Done when: a field morphing a word regular → bold matches captures at `t = 0` and `t = 1` against the static faces.

---

## 6. Order

| Step | Repos | Size | Needs |
|---|---|---|---|
| 1 | vexelray-gui | medium | canvas-looks.md §1 (glyph effects), §5 (text span quads); box span quads need nothing |
| 2 | vexelray-gui, kronometer for the beat demo | small | 1 |
| 3 | tactroller, vexelray-gui | medium | — |
| 4 | vexelray-gui | medium | 3 for paste with formatting; otherwise none |
| 5 | vexelray-gui | small | 1, canvas-looks.md §6 |

Decorations are the first effect that needs no engine change at all: a box-source span quad. They can land before
canvas-looks.md starts, and prove the span quad's placement, layering and offsets. 3 is independent of everything
and can run beside it.

## 7. Interactions

- **[runtime-themes.md](runtime-themes.md) R3** moves span colours onto paints. An effect's colours (a glow's, an
  underline's) are values today. They should index the same paint table once R3 lands, or a theme swap leaves them
  stale, which is the stale-pixel count that plan is driving to zero.
- **Typeset selection** ([todo.md](todo.md), "Selection. Insured, not built") would reuse step 3's HTML writer and
  clipboard, but not the field's selection model: its runs are two-dimensional. Out of scope here.
- **Selection across nodes** (dragging over several labels the way a web page allows) is a different feature: a
  selection over the tree, not over one document. Out of scope, and not implied by anything above.
- **Images and video with looks** are canvas-looks.md §4 and reach the GUI through `Node.image`. That is a small
  follow-on outside this plan, which is about text.
