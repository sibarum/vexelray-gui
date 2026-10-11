# Otter: the automation language `ottermate` speaks

Status: **Proposed 2026-10-10, not started.** Otter is the line protocol of [automation.md](../reference/automation.md)
§5, grown into a language on purpose. Every command that works today is already valid Otter, unchanged.
This plan adds three things: a way to **name** a node by what it is, a way to **read** a node and assert
on it, and a way to **look** at the screen as text. It also covers the client-side session that makes
working "one action at a time" practical.

Case-sensitive matching and leaving control flow open were decided on 2026-10-10 (§7).

The design is tuned for its main user so far, an agent that cannot see the screen, and §2 says what that
means. It is also meant to be pleasant for a person at a prompt, and wherever the two conflict the plan
says so.

---

## 1. What went wrong, from the session that motivated this

The `Unclip` session (2026-10-10) drove Vexplore to check a hover feature, and it needed five launches to
take four pictures. Each launch was one round of the same loop:

1. A script was written before the run. Its targets are coordinates or refs, and neither exists until the
   run does.
2. The run printed a `tree`, and the real coordinates were in it.
3. The script was corrected and relaunched. A drag on a column grip missed because the splitter drag
   before it had moved the grip, so the "corrected" coordinates were stale within the same run.

This was more than an inconvenience. It came from three gaps in the protocol:

- **Nothing can be named by what it is.** A target is a ref (minted per run), a landmark (only for nodes
  an application chose to name) or a point (a guess). "The tree row called *System Volume Information*" is
  something the semantic read-model can answer, but no verb accepts it.
- **Nothing can be read back.** The fact the session existed to check, *is this label drawn unclipped
  right now*, could only be judged by looking at pixels. A script cannot assert anything about a pixel,
  so the run could not be a test.
- **No session outlives a command.** With `--launch`, the application lives exactly as long as one
  script. Attaching works, but nothing makes "start it once, then poke it from separate calls" the easy
  path. So the agent wrote a whole script blind and relaunched, which is the loop above.

---

## 2. Who it is for, and what that changes

An agent and a person at a prompt need different things from the same instrument:

| | An agent | A person at a prompt |
|---|---|---|
| Sees the screen | No. Only through `shot`, which costs an image and returns a judgement, not a fact | Yes, usually in a window beside the prompt |
| Wants from a reply | Facts it can compare: exact, stable, greppable | Recognition: does this look like what I meant |
| Ambiguity | Fatal. A target that silently matched the wrong node becomes a confident wrong conclusion | Tolerable. They notice the wrong thing light up |
| Typing | Free | Expensive, so short forms matter |
| Memory between commands | None beyond the transcript | Good, plus the screen |

Four rules follow from this, and the rest of the plan applies them:

1. **A target resolves to exactly one node or it is an `err`.** The error lists the candidates and the
   selector that would have picked each one. First-match-wins is what makes automation flaky, and the
   flake shows up as a wrong answer rather than a failure.
2. **Every reply is a fact in a fixed shape.** `get` prints values, not prose. `expect` turns a fact into
   a verdict, and the verdict reaches the exit status the way every `err` already does (R5 in
   [automation-cli.md](../reference/automation-cli.md)).
3. **Durable names win.** A selector says what the node *is*: a landmark, or a role plus a name inside a
   landmark. A coordinate is still accepted, because a bug report quotes one, but nothing Otter prints
   will suggest one.
4. **Text before pixels.** `look` gives both users the spatial picture: what is on the screen, roughly
   where, and what got cut off. It costs a few kilobytes of text instead of an image, and both a person
   and a script can search it.

The case for 4 is not obvious, so here it is. For an agent a screenshot is expensive and imprecise. It can
answer "does this look right" and nothing else. A text readout of the same frame can answer "is the full
name visible", "what is next to the tree" and "did the dock cover the list", and it can be compared
between runs with `diff`. Your idea of a text browser like Emacs's for a person at a prompt turns out to
be the most useful of the three additions for an agent too.

---

## 3. The language

### 3.1 Shape: one statement per line, and that is the whole grammar of a script

A script is lines. A session is lines typed one at a time. The two are the same syntax, so anything
tried at the prompt can be pasted into a file, and a recorded session (§6) is a script with no
conversion. `#` comments and blank lines stay the client's own, as they are today.

**No loops or conditionals yet, but nothing rules them out.** Phase 1 is straight-line, because what no
caller can do today is name a node precisely and get an honest verdict about it, and that comes first.
Control flow is kept possible rather than planned, and the design keeps the door open in three ways:

- **New keywords cannot change an old line.** An unknown verb is an `err` today, so `repeat`, `if`,
  `each` and `end` are free to mean something later, and no script that runs now can contain them.
- **The condition is already a value.** Every reply begins `ok` or `err`, and `expect` makes any fact a
  verdict. So `if expect row ~"report" in @list exists` needs no expression language: the condition is a
  statement Otter already has.
- **It would live in the runner, above the protocol.** Like `let` and `use` (§5), control flow is about
  which lines get sent. The server would still see one line in and one reply out, so the protocol would
  not change. The runner is shared by the CLI and the macro player (§6), so this happens once for both.

**Why it is likely to be needed: macros.** Otter is meant to be the macro language too (§5, recording),
and a recorded macro is only reusable if it can react to what the screen did. The typical case is
`if expect dialog "Replace file?" exists`, then `click button "Yes" in dialog`. A macro also wants
parameters, such as the file it acts on, which is `let` again.

Both cases also set the limit. Otter's control flow is for reacting to the screen and never for
computing. The caller of a script already has a general-purpose language, and a second one growing
inside Otter is the thing to avoid when the time comes.

### 3.2 Selectors: one grammar, in every place a target goes

A selector is accepted wherever a verb takes a target today: `move`, `click`, `rightclick`, `drag`,
`scroll` and `go`, and `find`, `get`, `expect` and `look` as well.

```
target    := point | ref | landmark | query
point     := INT "," INT                         120,340
ref       := "#" INT                             #41          (a bare 41 still works: legacy)
landmark  := "@" NAME                            @tree        (a bare tree still works: legacy)
query     := step ( "in" scope )*
step      := ROLE? match? filter* pick?
match     := STRING                              "C:"         exact, case-sensitive
           | "~" STRING                          ~"volume"    contains, case-insensitive
filter    := "[" STATE "]"                       [focused] [visible] [clipped] [editable]
pick      := ":" ( INT | "first" | "last" )      row:2        1-based, in paint order
scope     := landmark | ref | step | "(" query ")"
```

What these look like in use:

```
click treeitem "System Volume Information" in @tree
move  textfield [focused]
get   row:2 in @list
drag  columngrip in columnheader "NAME" by -12em,0
drag  treeitem "a.txt" in @list to treeitem "Archive" in @tree
click button ~"save"
```

**Where a drag ends.** `drag <from> to <target>` drops on the centre of a node, which is drag and drop.
`drag <from> by <dx>,<dy>` moves relative to where the drag started, which is what resizing a column or a
splitter means: a distance, with no node at the far end. Offsets are **em by default** (a `px` suffix is
accepted), so a script resizes the same way at any zoom or density. That is the em rule the framework
applies to its own layout, applied to the one place a script states a distance. The existing
`drag <target> <target>` form, with two bare targets, still parses as it does today.

**How a query resolves.** Candidates are the published `SemanticSnapshot`, joined to `LayoutSnapshot`
by id, which is the same join `tree` already does. `ROLE` matches the role exactly. A match tests the
accessible name, and a node with no role is matched by name alone. `in` narrows the search to
descendants of a scope that must itself resolve to exactly one node. Exactly one candidate is the
answer; with a `pick` there may be many and the pick chooses. Anything else is an `err`.

**An ambiguous selector is an `err` that does the disambiguating for you:**

```
> click "c"
err 2 nodes match "c":
  #222 treeitem "c" in @tree        try: treeitem "c" in @tree
  #505 box "c" in @list             try: "c" in @list
```

Each suggestion is built by the same rule a recorder will use (§6): a landmark if the node has one, else
role and name inside the nearest landmark, else role and name with a pick. That gives an agent the next
command to type rather than a puzzle.

**Reaching the node is unchanged.** A resolved selector goes through today's `target()`: the centre of
`visibleRect`, revealing it through its landmark when it is concealed, otherwise refused with a reason.
The selector only says which node; it does not change how a click reaches it.

**Backwards compatibility.** A single bare token keeps its current meaning: digits are a ref, a comma
makes a point, anything else is a landmark. Every new form is either sigilled (`#`, `@`, `~`, `:`, `[`)
or has more than one token. So no line that parses today parses differently tomorrow. Roles are never
bare on their own for the same reason: `button` alone remains the landmark `button`, and a role needs a
name or a filter after it (`button "Save"`, `button [focused]`).

### 3.3 Reading: `get`

```
> get treeitem "System Volume Information" in @tree
ok #330
role      treeitem
name      + System Volume Information
landmark
box       15,655 114x28
visible   15,655 114x28
clipped   no
focused   no
editable  no
in        @tree
```

One `key value` line per published fact, in a fixed order, so `grep '^clipped'` works. `get <sel> <key>`
prints only the value (`ok 15,655 114x28`). A field that is not published is an `err` naming the
read-model that would have to carry it. It is never printed as empty, because an empty value reads as a
real one.

Phase 1 `get` covers **only what is published today**: the semantic node (role, name, landmark, text,
caret and selection, focused, editable, visible) and the layout node (box, `visibleRect`, clipped,
content and scroll). The property this session actually needed, `unclip`, is **not** published and
cannot be read without reaching into `RetainedNode`, which automation.md §10 forbids. That is the right
constraint, and §5's paint read-model is how it gets met.

### 3.4 Verdicts: `expect`

```
expect <target> exists | absent | visible | hidden | focused | clipped | unclipped
expect <target> <key> = <value>
expect <target> <key> ~ <text>        contains, case-insensitive
expect <target> <key> != <value>
```

A passing `expect` replies `ok`. A failing one replies `err` with what was there, for example
`err expected clipped = no, was yes (box 46,658 230x22, visible 46,658 79x22)`. That `err` stops a
script and sets exit status 1 through the machinery that already exists. A scene ladder becomes a test
without the client learning anything new.

`expect` reads the frame that is current when it runs; it does not wait. Waiting is `await` (§5), and
the two stay separate for the reason [automation-cli.md](../reference/automation-cli.md) §5 gives for
`settle`: an instrument that blurs "is" with "eventually is" passes cases a person fails.

### 3.5 Looking: `look`

```
look [<target>] [cols <n>] [refs]
```

`look` draws the visible text of the window, or of one node's `visibleRect`, onto a character grid. It
uses the published read-models only.

- **The grid.** `cols` defaults to 120. One column is `width / cols` pixels and one row is two columns'
  worth, which matches the shape of a terminal cell, so the picture keeps its proportions.
- **Text.** Each visual line of every text node (`TextMetrics` gives the lines, and their x positions
  are already baked) is written at the cell its line starts in. **It is clipped to the node's
  `visibleRect` in cells**, so a name the column cuts off is cut off in the readout as well. That is
  the point: the readout shows truncation, and "Document" where "Documents" should be is something
  `grep` can catch.
- **Overlap.** Nodes are written in paint order (tree order, with floating nodes last, as
  `TreeRenderer` paints them), so the one on top wins a cell.
- **Frames.** Landmarks are boxed with ASCII rules and labelled `@name`, because a landmark is where an
  agent will aim next and a person needs the regions. The focused node is bracketed `>...<`.
- **`refs`** adds each interactive node's ref as a margin note, for a person who wants to click what
  they can see.

What the Vexplore frame from the `Unclip` session would print:

```
Vexplore                                                                        _  []  x
 C:
+@tree------------+-@list----------------------------------------------+-@rail--------
|+ Desktop        | NAME             TYPE    SIZE     MODIFIED         | Suggestions
|+ Document       | pagefile.sys     SYS     11 GB    Today 19:30      |
|+ Download       | System Volume    FOLDER           Today 00:12      |
|+ Music          | DumpStack.log.   TMP     12 KB    Yesterday        |
|- C:             | swapfile.sys     SYS     16 MB    Yesterday        |
|  + $Recycle     | hiberfil.sys     SYS     6.17 GB  Yesterday        |
|  + System Volu  | Windows          FOLDER           Oct 6            |
+-----------------+----------------------------------------------------+--------------
 25 items                                                                          C:\
```

Every cut-off name in it is a finding that a screenshot would only have *shown*.

Two honest limits, both written into the reply's header line rather than left to be discovered. First,
**it shows text, not paint**: a plot, an icon and a marched viewport are blank boxes in it, and `shot`
is still how those are judged. Second, until the paint read-model exists (§5), **it cannot show an
unclipped node drawn over its neighbours**, because that fact is not published. `look` shows the clip
and not the lift until then.

### 3.6 The session: start once, then talk to it

All of this is client-side. The protocol does not change.

```
ottermate start --launch mvn.cmd -q exec:exec -Dautomation=0
ok session 1 on localhost:63779 (pid 3444)
ottermate click treeitem "C:" in @tree
ottermate look @tree
ottermate stop
```

`start` launches the application as `--launch` does, waits for the port, writes it with the pid to a
session file under the client's working directory (`.ottermate/session`), and **returns, leaving the
application running**. Every later invocation without `--port` attaches to the session's port if a
session file exists, and otherwise to 7654, as today. `stop` shuts the application down the way
`--launch` already does at the end of a run, including a `mvn` grandchild, and deletes the file.

A stale session file is checked, not trusted. If its pid is gone, the client says the session ended,
names the exit status if it knows it, and deletes the file. Falling through to 7654 is exactly the
failure this tool exists to prevent: driving some other application and reporting success.

This is what makes "one action at a time" real. Each command is a separate call against a live
application, so the agent's loop is *look, act, look* rather than *guess a whole script, launch, read,
relaunch*.

### 3.7 Windows, dialogs and prompts

**How it works today.** Each window has its own tree, bus and pointer, and `Windows` gives each one its
own `Automation`. The `window <name>` verb chooses which window receives the next command, and
unchosen commands go to the main window. Dialogs from `Modals` are separate windows (`requestWindow`,
`Standing.SATELLITE`). They are made modal by `GuiApp.applyModal`, which disables every other window
through the OS (`NativeWindow.setEnabled`). Context menus and `Select` popouts are not windows: they are
floating nodes in the tree they belong to.

**The gap, which is a defect in today's instrument and not only a missing feature.** Modality is
enforced by the window manager, and automation publishes input onto a window's bus beneath it.
`WindowView` carries no enabled state and `Automation` never asks for one. So while a dialog is up, a
`click` sent to the main window is published, lands, and answers `ok`, which is something a person
cannot do. This is the automation.md §6 failure exactly: an action the real input path cannot take.
(Read from the code; Phase 1 proves it with a failing test before fixing it.)

**The design, in four rules:**

1. **A window that cannot take input refuses it.** `WindowView` gains the fact that `applyModal`
   already knows (enabled, and if not, which window blocks it). Every acting verb aimed at a blocked
   window answers `err window "main" is blocked by dialog "Unsaved changes"`. Reading verbs (`get`,
   `look`, `tree`) still work, because a person can see a window behind a dialog.
2. **A selector searches every open window, and the exactly-one rule applies across them.** An agent
   should not need to know which window a button is in before naming it. The window a node is found in
   decides which driver acts, so the right bus and pointer are used without a `window` command. Two
   windows can both have an `@theme.accent` (`Address` says so explicitly), and that is an ambiguity
   error with suggestions, like any other.
3. **Windows and dialogs are scopes.** `window "Preferences"` is a step that matches a window by name;
   `in window "Preferences"` narrows to it, and `@Preferences/theme.accent` is the `Address` form the
   framework already parses. `Modals` gives its content the role `dialog`, named by the title, so the
   commonest case reads as what it is:

   ```
   click menuitem "Close" in menu
   await dialog "Unsaved changes" exists
   expect dialog "Unsaved changes" name = "Unsaved changes"
   click button "Discard" in dialog "Unsaved changes"
   expect dialog "Unsaved changes" absent
   ```

   `window` remains as a verb, and it now sets which window an *unscoped legacy* target (a bare ref,
   landmark or point) means. That is exactly what it does today.
4. **Opening a window is asynchronous, so it is awaited, never assumed.** `requestWindow` returns before
   the window exists. A script that clicks "Close" and then immediately addresses the dialog races the
   window into existence. `await <target> exists` (§5) is the honest wait, and until it lands
   `expect` fails truthfully. Phase 1's `settle` does not cover a window that has been requested but not
   yet created.

**Prompts** are dialogs with a field in them, and need nothing new:
`click textfield in dialog "Rename"`, then `type "report-final.pdf"`, then `key ENTER`. Later sugar
(§5): `type "report-final.pdf" into textfield in dialog "Rename"` does the click to focus first. It
still sends the characters one at a time through the ordinary path, never by setting the text directly.

**Native OS dialogs are outside the instrument, and it has to say so.** A system file picker (`nfd`, used
by applications in sibling repos) or an OS message box is not a VexelRay window. It has no tree, no bus
and no Tactroller input, so Otter cannot see or drive it. Today it shows up as every command timing out
while the application waits on a window the driver doesn't know exists. The plan for it (§5) is in two
parts. First, **detection**: native dialogs go through one framework seam that knows when one is open, so
`windows` lists `native file dialog (not drivable)` and acting verbs answer `err` immediately instead
of timing out.

Second, **answers queued in advance.** A script states the answer *before* the action that will open the
dialog. The framework's seam checks the queue when the application asks for a dialog: if an answer is
waiting, it returns that answer and the OS dialog never opens. If none is waiting, the real dialog
opens, and detection reports it.

```
answer filedialog open "C:/x/report.pdf"
click menuitem "Open..." in menu
await @status name ~ "report.pdf"
answer filedialog save cancel
click button "Export"
expect answers none
```

The rules that keep it honest:

- **A queue, first in, first out, one answer per dialog.** Two queued answers serve the next two dialogs,
  in order. An answer is spent the moment a dialog takes it.
- **Typed by kind:** `open`, `save` or `folder`, and an answer is a path or `cancel`. An answer is only
  taken by a dialog of its own kind. A `save` that finds an `open` answer at the head of the queue does
  not take it, and opens for real, so detection reports a dialog the script did not expect. Handing a
  save dialog an open dialog's path would be a confident wrong run.
- **Unused answers are a finding.** `answers` lists what is still queued, `expect answers none` asserts
  the queue is empty, and `stop` reports anything left over. An answer nobody took means the action
  never asked for a dialog, which is usually what the test was there to find out.
- **Visible everywhere it acts.** Each answer taken writes a row to the correlation log (which dialog,
  which answer, and that the OS dialog was skipped), so a run read back later shows the substitution
  rather than looking like a person chose the file.

It is not perfect, and the plan says so. The OS picker itself is never exercised, so a bug that only
shows when the real dialog is open (focus coming back, or a filter that hides the file) is out of reach.
What it unblocks is every test of what the application does *with* a chosen file, and that is the
application's own code, running on its real path.

It also keeps the instrument **platform-agnostic**, which is worth as much as what it unblocks. Driving
the real picker would mean UI Automation on Windows and other APIs on every other OS. That is a
per-platform input path, outside Tactroller, for a dialog that is not the application's. The seam sits
where the application asks for a file, a question with the same shape everywhere, so the answer queue
is one piece of code on every platform.

---

## 4. Phase 1: what I would build now

The things that would have turned the motivating session from five launches into one. Each item is
small, and each can be shipped on its own.

| # | Piece | Where | Size |
|---|---|---|---|
| 1 | **`Selector`**: the grammar of §3.2 as a parsed value (role, match, filters, pick, scope chain), resolution against a semantic plus layout snapshot pair, and the ambiguity error with its suggestions. A class of its own beside `Automation`. | `vexelray-gui-automation` | ~300 lines plus tests |
| 2 | **Selectors in every target slot**, through `Automation.target()`, and `find` accepting a selector as well as its current substring. | same | small |
| 3 | **`get`** and **`expect`** over published facts (§3.3, §3.4), and **`await <target> exists \| absent`**, the least `await` that lets a script wait for a dialog to open or close (§3.7 rule 4). | same | ~250 lines |
| 4 | **`look`** with the grid, text, clipping, paint order and landmark frames. `refs` can wait. | same | ~250 lines |
| 5 | **`start` / `stop`** and the session file (§3.6). | `vexelray-gui-automation-cli` | ~150 lines; the client keeps its empty dependency block |
| 5b | **The runner, moved out of the client**: the comment and blank-line rules, stop-at-first-`err` with `--keep-going`, and the verdict, in a new empty-dependency module the client depends on. It adds no behaviour in Phase 1. It exists so that `let`, `use` and control flow have one home that the macro player can share. | `vexelray-gui-otter` (new) | ~100 lines moved, plus their tests |
| 5c | **Modality is honoured** (§3.7 rule 1): first a failing test that clicks the main window behind a dialog, then `WindowView` carrying the blocked state, and acting verbs refusing a blocked window. `windows` shows `modal` and `blocked by`. | `vexelray-gui-core` (`WindowView`, `GuiApp`), `vexelray-gui-automation` | small; this fixes a defect that exists today |
| 5d | **Selectors across windows** (§3.7 rules 2–3): candidates from every open window, a `window "..."` scope step, the `Address` form for landmarks, and the role `dialog` on `Modals`' content. | `vexelray-gui-automation`, `vexelray-gui-widget` (`Modals`) | ~100 lines on top of item 1 |
| 6 | **The automation socket in `vexelray-gui-demo`.** The gallery is where framework widgets are checked, and today nothing can drive it: the `Unclip` demo chapter was never looked at. | `vexelray-gui-demo` | a few lines; see the note below |

**Note on 6.** `vexelray-gui-demo` would depend on `vexelray-gui-automation`, a module already in this
reactor, and open the server only behind `-Dautomation`, as the framework's `Driver` does.
automation-cli.md R7 forbids automation in a *shipped* application, and the gallery is not one.

**Tests.** `Selector` is tested against hand-built `SemanticSnapshot`/`LayoutSnapshot` pairs: every form
in §3.2, ambiguity, picks, scope chains and the legacy single-token forms. Verbs are tested through
`HeadlessGui` the way `AutomationTest` already tests them. `look` is tested with golden text over a
small tree whose geometry is fixed, plus one case that asserts a cut-off label is cut off in the
readout. The client is tested against its existing fake server, plus a fake child process for
`start`/`stop` and for the stale-session path.

**Done means** the motivating session can be replayed as one `start`, a handful of single commands
addressed by selector, an `expect ... clipped = yes`, a `look`, and one `stop`, with no coordinates
anywhere in it. The two drags that needed them are `drag splitter in @split by -10em,0` and
`drag columngrip in columnheader "NAME" by -12em,0`.

---

## 5. Later, and how each fits without breaking anything

Each later addition is a new verb, a new key, a new filter or a new optional argument. None of them
changes the meaning of a line that already parses, so a script written against Phase 1 keeps working.
`help` will report `otter 1` and later `otter 2` and so on, so a script can say which version it needs
(`otter 2` on its own line is a no-op that fails on an older server). That is the whole compatibility
mechanism.

- **A paint read-model.** This is what `unclip`, `opacity`, `translate` and the effective background
  need, and so is `look` drawing a lift. It is a third published snapshot of draw-time facts, written by
  the same compute-phase writer, keyed by id, carrying the same version, and guarded the same way. It is
  framework work, not automation work, and it is owed for the same reason `SemanticSnapshot` was: a thin
  client has to know what is painted. Once it exists, `get` gains a `paint` group and `expect @x unclip = 1`
  becomes legal. The keys are new, and no old key changes.
- **Semantic states the read-model lacks: `selected`, `expanded`, `checked`, `enabled`.** These are new
  `SemanticNode` fields that widgets declare as they already declare roles, then new filters
  (`row [selected]`) and new `get` keys. Driving the tree made the gap concrete: whether `C:` was open
  could only be read off its name, `"+ C:"` against `"- C:"`, which is a glyph in a label and not a state.
- **Cells by column.** `cell "NAME" of row ~"short.txt" in @list` needs the semantic link between a cell
  and its `columnheader`, which `Table` knows and does not publish. Driving Vexplore turned up the
  practical reason: a row's accessible name was `"JPG 0 B Today 19:32"` and left out the file name,
  because the name column holds an icon and a label rather than a label. Fixing the row name is a
  `Table` change worth making whatever Otter does.
- **`await <target> <predicate>`.** The general form of today's `await <landmark> <text>`, with the same
  predicates as `expect`: `await row ~"report" in @list exists`. Today's two-argument form stays, as the
  case where the target is a bare landmark and the predicate is `name ~`. Phase 1 ships only `exists` and
  `absent`, which is what waiting for a dialog needs (§3.7 rule 4); this later step adds the rest.
- **`type <text> into <target>`.** Clicks to focus first, then types one character at a time through the
  ordinary path (§3.7, prompts).
- **Native dialogs** (§3.7): a framework seam that every native dialog goes through, so the instrument
  can report one as open instead of timing out; then answers queued in advance
  (`answer filedialog open <path>|cancel`, `answers`, `expect answers none`). This is cross-repo
  work, because the `nfd` callers live in sibling repos, and that is why it is not in Phase 1.
- **`shot <path> of <target> [scale <n>]`.** A crop of the read-back frame to a node's `visibleRect`,
  enlarged. This session did that with System.Drawing after the fact.
- **`let` and `$name`.** `let row = treeitem "C:" in @tree`, then `click $row`. This is textual
  substitution in the client, done before a line is sent, so the server never sees a variable and the
  protocol stays one line in, one reply out. `let n = get @items name` captures a value.
- **`use <file>`.** Includes another script, for shared setup.
- **Key repetition.** `key DOWN *3` is three presses through the ordinary path, never auto-repeat, and
  only for command keys. This is the framework's own repeat rule, so `type` takes no count at all.
- **`look refs`**, and `look diff <file>` comparing the current readout to a saved one, which turns a
  readout into a regression check without involving pixels.
- **Recording (automation.md §7's macro-record).** The title-bar instrument writes **Otter**. Each
  recorded press is written as the most durable selector for what was under the pointer, chosen by
  §3.2's suggestion rule. That rule is shared on purpose: the ambiguity error and the recorder produce
  the same selector, so a person who records a session and an agent who reads an error are taught the
  same names.

- **Control flow**, when a real script needs it: kept possible, not designed yet (§3.1).

**Not planned:** JSON output, because the `key value` shape is already
stable and greppable, and a second format is a second contract; and regular-expression matches, until
an exact match plus `~` contains has demonstrably failed on something real.

---

## 6. Where the pieces go

- **The server resolves every selector.** A selector names a node in a particular frame, and only the
  process holding the read-models knows that frame. A client-side resolver would act on the frame it
  last read, which is the stale-coordinate flake under a different name.
- **A runner owns everything above one line: comments and stop-at-first-`err` now; `let`, `use`,
  parameters and control flow later.** It is its own module, `vexelray-gui-otter`, with an empty
  dependency block, written against a line-in, reply-out function: the same shape as `Commands`, which
  `Automation` already implements. `ottermate` runs it over the socket. The macro player (automation.md
  §7) will run it in-process against `Automation`. One runner means a macro means the same thing wherever
  it is played: a construct that existed only in the CLI would make recorded macros a second dialect that
  the title bar cannot play. It stays out of the server for the same reason as before: the protocol stays
  one line in, one reply out.
- **The client owns sessions** (§3.6), the one thing that really is about a process and a port.
- **Every resolution path shares the suggestion rule.** The ambiguity error, the `in @landmark` shown in
  `find`, and the future recorder all choose a selector for a node the same way, from one function in
  `Selector`.
- **The language reference moves to `docs/reference/otter.md` once Phase 1 lands.** This plan then
  becomes the record of how it was decided, as automation-cli.md is for the client.

---

## 7. Decided, and still open

**Decided 2026-10-10:**

- **A name match is case-sensitive by default**, and a metacharacter makes it forgiving: `~"save"`
  matches case-insensitively and by containment. Names are data, and a match that ignores case without
  being asked to will one day match two nodes.
- **Control flow is left open** (§3.1): not in Phase 1, not ruled out, and the three properties that
  keep it possible are kept. It is for reacting to the screen only.
- **Otter is expected to be the macro language too**, so whatever sits above a single line goes in a
  runner that the CLI and the in-app macro player share (§6), never in the CLI alone.

**Open:**

- **What counts as a candidate.** The proposal is every node that `tree` lists: one with a role, a name,
  a landmark, or that is focusable. A bare unnamed box is unreachable by selector, as it is
  unreachable in `tree`. The answer for one of those is a landmark.
- **Whether `look`'s frames should box only landmarks or every role that groups children** (`table`,
  `tree`, `rowgroup`). Landmarks only to begin with, because more frames make a busier picture. The
  first real use will settle it.
