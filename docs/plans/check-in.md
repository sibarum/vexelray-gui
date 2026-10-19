# Check-in and check-out: the person as a holder of the hand

Status: **Proposed 2026-10-18, not started.** It builds on [background-runs.md](background-runs.md), which is
landed: an application an agent starts is cloaked and unreachable, and its notification-area icon carries a menu
whose last entry is Exit Program. This plan adds the other thing a person at the desk needs from that menu, which
is to step in without ending the run, and the matching thing an agent needs, which is to hand the application to
the person and carry on when they are done.

## The idea, and the one rule it comes down to

The automation socket already has **the hand** ([automation.md](../reference/automation.md), *Many observers, one
hand*). Any connection may read, and only the one holding the hand may drive. Taking and releasing it are
recorded, and letting go releases whatever the holder left held. Until now the person has never been one of the
holders: in a background run they cannot touch the application at all.

**Check-in** makes the person a holder. **Check-out** gives the hand back. An agent's own check-out, `hand give`,
offers the hand to the person, and its next `settle` resolves when they give it back. They are one mechanism, the
hand, with a second kind of holder, and one rule decides everything else:

> **Whoever holds the hand is the only source of input, and the window's presence follows the hand.**
> Agent: cloaked, real devices ignored, injected input flows. Person: uncloaked, reachable, real devices flow,
> injected input waits.

Every effect follows from moving the cloak. Tactroller already decides *unattended* from `DWM_CLOAKED_APP` on
the window (background-runs.md), so if it reads that as it changes rather than once at attach, the input source
follows the hand with no switch of its own. Nothing gets a second copy of the fact.

This applies to background runs only. In a run the person started, they always have the real devices, and an
agent driving it alongside is a different problem, out of scope here.

## When a check-in takes effect: at a boundary, never mid-gesture

The hand changes holder **at the next command boundary with nothing held**, not after some stretch of quiet.

- **A waiting verb is already at a boundary.** `settle` and `await` are not acting, so a check-in during one takes
  effect at once. The verb does not fail: it goes on waiting, now for the hand to come back (see *Unsettled*).
- **A driving verb runs to its end first.** A `click` finishes its press and release, a `key CTRL+SHIFT+W` its
  whole chord, a `drag` its path. Handing the person half a chord is the one way this could do harm: Ctrl held by
  nobody, landing on the person's first keystroke.
- **Nothing may be held across the change.** A script that did `keydown SHIFT` or `down` is not at a boundary
  until it lets go. To keep a script that never lets go from shutting the person out, a **hold limit** (3 s
  proposed) releases everything held, last pressed first, exactly as `hand release` does today, and the hand
  moves. The release is recorded on the probe as `agent.hand #2 release (check-in, held keys dropped)`.

A check-in from the tray therefore feels instant whenever the agent is waiting, which is most of the time, and at
worst takes as long as one gesture plus the hold limit.

## Unsettled: what the agent's connection sees while the person holds the hand

- **Reading stays free.** `tree`, `find`, `where`, `shot`, `windows` and the recorder verbs work as they always do
  for observers. An agent may watch what the person does, and an Inspector certainly will.
- **Driving waits, it is not refused.** Between connections a driving verb from a non-holder is refused, because
  that holder could be anything. The person is a temporary holder who will give the hand back, so a driving verb
  waits for it. On reaching the verb's timeout it answers **`pending`**, not `err`:
  `pending the person holds the hand (since +41.0s)`.
- **`settle` is unsettled.** The application is not settled while the person has it, whatever its frame queue
  says, so `settle` waits and answers `pending` on its timeout the same way.

**Coming back.** When the hand returns, the waiting verb resolves, and its reply says what happened while it was
away: `ok v52 hand back after 1m12s (v14 -> v52)`. Without that, the agent carries on from a picture the person has
since changed.

**No acting on a stale picture.** After a hand return, the connection that had the hand is marked **stale**. Its
next driving verb is refused once, with `err stale: the person had the hand (v14 -> v52); read before acting`,
until it reads (`tree`, `find`, `shot` or a `settle`, whose reply above already counts). The invariant: no
connection drives from a view older than the last hand return. A script ladder is the case this exists for: its
next step assumed the screen the person just changed.

**Whom the hand returns to.** The connection that held it before the check-in, if it is still connected, otherwise
nobody (free).

## The agent's side: `hand give`, an offer rather than a grab

```
> hand give Please sign in with the test account, then hand it back.
ok offered to the person
> settle
pending the person has not taken the hand yet (offered +30.0s)
> settle
ok v52 hand back after 1m12s (v14 -> v52)
```

If the agent's handing-over uncloaked the window, the window would take the foreground, which breaks the rule
background runs exist for. So `hand give <message>`:

- **Announces the offer from the icon**: a notification-area notification (`NIF_INFO` on the tray's own icon, no
  window shown) with the message, and the menu's first entry changes to *Check-in: <message>*.
- **Leaves the window cloaked until the person accepts**, by clicking the notification or choosing Check-in.
  Accepting is an ordinary check-in, and since the person asked, the window may take the foreground.
- **Commits the connection.** From the offer on, its driving verbs wait exactly as if the person held the hand.
  `hand take` withdraws an offer nobody has accepted yet.

Notifications can be suppressed (Focus Assist, Do Not Disturb), so the menu label carries the request too.

## The menu

Built on every click already ([background-runs.md](background-runs.md)), so the entries simply reflect the hand:

| Hand | Entries |
|---|---|
| Agent holds it, or free | **Check-in**, then the separator and **Exit Program** |
| An offer is open | **Check-in: <the agent's message>**, then the separator and **Exit Program** |
| Person holds it | **Check-out**, then the separator and **Exit Program** |

The person's check-out recloaks the windows. The foreground goes wherever Windows sends it when the window leaves,
which is the person's previous window.

## Where each piece lives

1. **`vexelray-os` (repo `vexelray`): presence becomes a state.** `BackgroundRun.active()` stays as it is: the
   process is a background run. A new process-wide **presence**, `AGENT` or `PERSON` with a listener, says which
   way the windows face. `Win32Window` cloaks and uncloaks every window on a change. The foreground rules
   (`focus()` only makes a window exist, show without activating, the minimized emulation) apply in `AGENT`.
   Switching to `PERSON` restores a window minimized in the background as the ordinary minimized window it
   stands for. Menu entries gain a label computed per click (`addMenuItem(Supplier<String>, Runnable)`), and
   `Win32Tray` gains the notification. **Test:** `BackgroundRunWindowTest` grows a round trip. Going to `PERSON`
   uncloaks and makes the window hittable, and going back to `AGENT` recloaks it, with the foreground checked at
   each step as now.
2. **`tactroller`: unattended is read as it changes.** Re-query `DWMWA_CLOAKED` at most every 100 ms, and at
   every foreground change, instead of once at attach. Leaving unattended re-primes the pointer, so the person's
   cursor position is not delivered as a jump. **Test:** cloak and uncloak a window under an attached backend,
   and check that real-device state is withheld only while cloaked.
3. **`vexelray-gui-automation`: the person as a holder.** The pending check-in, honoured at the next boundary;
   the hold limit; `pending` for waiting driving verbs and `settle`; the reply after a hand return and the stale
   mark; `hand give` and withdrawing an offer; the tray entries, registered through `BackgroundRun`. The hand's
   probe marks gain `person` as a holder. **Tests** at the `Commands` level with no socket, as the hand's are
   now: check-in during `settle` (instant), during a held `keydown` (waits, then the hold limit releases), and
   a script step after a return (refused once, as stale).
4. **ottermate:** a `pending` reply is retried until ottermate's own `--timeout`, and then exits with a new status
   **5**, *the person holds the hand*, with the message saying to run it again to keep waiting. A stale refusal
   inside `--script` stops the script, as an `err` does, because a ladder's next rung assumed the screen the person
   changed. Docs: the guide's background-run section, and the status table.

Steps 1 and 2 are independent of each other. Step 3 needs step 1's presence, and step 4 needs step 3's replies.

## How to know it works

- From the tray, during an agent's `settle`: the window appears at once, the person's typing reaches it, and the
  agent's `settle` answers `pending`. After Check-out, `settle` answers `ok … hand back …`, and the agent's next
  `click` without reading first is refused as stale.
- During a script holding `keydown SHIFT`: the check-in lands within the hold limit, and Shift is not held in the
  person's hands.
- `hand give` while the person is typing elsewhere: no change of foreground, a notification appears, and the menu
  shows the request.
- `BackgroundRunWindowTest` still finds no step of an `AGENT` presence that moves the foreground.

## Open questions

- **The hold limit:** 3 s, or the length of the longest gesture the verbs can produce?
- **Should check-in raise the window?** Proposed yes, since the person asked for it in that click. The
  alternative is uncloaking in place and letting them Alt-Tab to it.
- **The tray notification on Windows 11** may arrive as a toast in the notification centre rather than a balloon.
  Either is acceptable. The menu label is the guaranteed channel.
- **Reading the cloak in tactroller:** polling every 100 ms is the simple answer. The alternative is
  re-attaching the backend on a presence change, through `WindowInput`, which ties tactroller's state to an
  explicit event but routes it through the GUI's input seam.
