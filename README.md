# Gnomeball

A [RuneLite](https://runelite.net/) plugin for organizing and refereeing **Gnomeball** — a flag-football-style minigame played using a Gnomeball (or the F2P-accessible Peaceful handegg). The plugin turns any open patch of ground into a Gnomeball arena: it lets a host mark out a field, form two teams plus referees, and then handles scoring, turnovers, tagging, and timekeeping live as players move around in-game, with a synced scoreboard/timer overlay for everyone in the game.

It is a thin client over a small companion backend (a separate "Gnomeball API" server) that is the source of truth for game state — the plugin's job is to observe the game world, translate player actions into API calls, and render the server's authoritative state back onto the screen.

## Summary

- **Host** creates a game from the sidebar panel, gets a shareable join code, and marks out a field (either a built-in preset, a customized field, or a saved layout) with team end zones.
- **Players** join with the code and get enlisted onto **Team A**, **Team B**, or as a **Referee** via a right-click menu on another player.
- Once the host starts the game, a shared clock and scoreboard appear on everyone's screen. Team players carry the Gnomeball across the map, pass it to teammates, tag the ball carrier to force a turnover, and score by carrying the ball into their own end zone.
- The plugin only enforces things it can see client-side (position, animations, inventory, equipment) — actual game state (score, roster, ball possession, timer) is owned by the server and mirrored down to every client in real time.

## Rules & Mechanics

### Roles
- **Team A / Team B** — the two playing sides.
- **Referee** — a neutral role that goals are delivered to after being scored (see Obligations, below), and who can pause/resume the clock.
- **Observer** — the default state for anyone not yet enlisted; not part of gameplay, but can still see what's happening.

Enlisting is a host action: right-clicking a player's **Follow** option in the LOBBY or ACTIVE phase surfaces an **Enlist** submenu (Team A / Team B / Referee), or the host can reassign anyone from the roster panel. Menu entries for enlisted players are recolored in their team/referee color, and non-roster players are excluded from any menu targeting them once a game is ACTIVE, to avoid accidentally interacting with (or passing to) a bystander.

### The ball
Either the **Gnomeball** or the **Peaceful handegg** (a Free-to-Play-accessible substitute) is treated as the game ball. Whoever the server says is holding it gets a floating ball icon over their head and on the scoreboard.

### Passing
The current ball holder can pass by using the ball on another player (a "Use ball -> Player" style target). A pass only registers if:
- the sender is currently the ball holder,
- the sender is an enlisted Team A/B player,
- the item being used is a Gnomeball/handegg, and
- the target has a free weapon-slot (i.e. isn't already holding a ball themselves).

A pass to a player on the *opposing* team is flagged and flashed on-screen as an **interception**.

### Tagging
A team player can force a turnover by "attacking" the ball carrier (in melee range) while wielding one of the designated tagging props: **Rubber chicken**, **Stale baguette**, or **Beach boxing gloves** (yellow or pink). A successful tag only counts if the attacker is on the opposing team from the ball holder. The tagged player is stunned (visual effect only) and is obligated to hand the ball to their tagger; a brief immunity window follows once that hand-off completes so they can't be immediately re-tagged.

### Scoring
The field is marked with **Zone A** and **Zone B** tiles (each team's own end zone). Carrying the ball into your own zone scores a goal for your team, triggers a 3-second "GOAL!" banner, and increments the scoreboard.

### Out of bounds
If the host has marked out field tiles and the ball carrier steps outside all of them, it's ruled out of bounds — no goal is possible until the ball is turned over.

### Obligations
Scoring a goal or going out of bounds doesn't just add/subtract points — it locks further scoring until the ball changes hands per the game's flow:
- **After a goal:** the scoring team must deliver the ball to a **Referee** (or, if no referee is present in the game, to any player on the opposing team).
- **After an out-of-bounds:** the ball must go to a member of the **opposing team** specifically — a referee never fulfills this one.

No further goals or turnovers are recognized while an obligation is outstanding. The host can always force-clear it (and the ball) manually if a player won't cooperate.

### Timer & referee controls
The host starts the game with a chosen duration, which starts a shared countdown for all clients. A referee can **blow the whistle** to pause the clock (broadcasting a "whistle" flash to everyone), start/stop it again, and **set the clock** directly to a specific minutes/seconds value (whether currently running or paused) — useful for correcting drift or adding/removing time. The host can end the game at any time, correct either team's score directly, rename teams, or broadcast a text announcement that flashes on every player's screen.

### Field building
Hosts mark tiles as **Field**, **Zone A**, **Zone B**, or a plain **Standard** marker via a right-click menu (while in placement mode) or the sidebar's preset tools:
- **Standard Field** — a built-in 25×10 field with 3-tile-deep end zones.
- **Custom Grid** — a host-dimensioned rectangular field (W × H, filled interior).
- **Saved slots** — up to 3 custom layouts (arbitrary/irregular shapes) captured from whatever's currently marked and recalled later.

Any preset can be rotated in 90° steps before being committed, and the exact same placement math drives both the live preview and the final commit so they can never disagree. Placing/removing a preset acts on every tile in its footprint, clearing whatever was there before (so overlapping zone-on-field markings don't leave stray leftovers). Zones can also be drawn freehand, tile by tile.

## Architecture

The plugin is a **client-only RuneLite plugin** (`GnomeballPlugin`, entry point `gay.runescape.gnomeball.GnomeballPlugin`) that talks to a separate, self-hosted **Gnomeball API** server over HTTP. The server is the single source of truth for all shared game state (roster, score, ball possession, obligations, timer, tiles); the plugin never derives authoritative state purely from local observation — it reports candidate events to the server and waits for the server to echo them back before updating shared UI.

```
RuneLite client                         Gnomeball API server
┌─────────────────────────────┐         ┌───────────────────┐
│ GnomeballPlugin              │  REST   │  game state store  │
│  ├─ ApiClient    ───────────►│◄───────►│  (roster, score,    │
│  ├─ EventPoller  (poll 2Hz)  │         │   ball, tiles,      │
│  ├─ RosterReducer            │         │   timer, obligations)│
│  └─ TileReducer              │         └───────────────────┘
│                               │
│  UI:                          │
│   ├─ GnomeballPanel (sidebar) │
│   ├─ PlayerOverlay            │
│   ├─ TileOverlay               │
│   └─ TimerOverlay              │
└─────────────────────────────┘
```

### Key components

- **`GnomeballPlugin`** — the RuneLite plugin lifecycle and the hub everything else hangs off. Subscribes to RuneLite events (`GameTick`, `MenuEntryAdded`, `MenuOptionClicked`, `AnimationChanged`, `GameStateChanged`), translates in-game actions (stepping into a zone, passing, tagging, going out of bounds) into API calls, and applies server-pushed events back into local state. Holds all live game state as `volatile` fields (phase, score, ball holder, obligation, timer, field-editing mode, etc.) read by the panel and overlays.

- **`ApiClient`** — a thin OkHttp/Gson REST client for the backend (`/v1/games/...` endpoints): create/join/start/end a game, pass/tag/score/report-out-of-bounds, mark/unmark tiles, roster/tiles/events reads, timer and host-broadcast actions. Write actions (anything host-only) are authenticated with a per-game **write key** bearer token issued at game creation; read/report actions require only the game ID.

- **`EventPoller`** — polls the server's `/events?afterSeq=` endpoint at a fixed 500ms interval and hands each new event to the plugin in sequence order. This is how every client — host or not — learns about goals, tags, roster changes, and timer changes without a persistent connection.

- **`RosterReducer`** / **`TileReducer`** — small reducer-style caches that apply server events (`PLAYER_JOINED`, `ROLE_ASSIGNED`, `TILE_MARKED`, etc.) or full snapshots onto local maps, giving the rest of the plugin a synchronous, query-able view of "who's on what team" and "what tiles are marked as what" without re-deriving it from raw events each time.

- **`FieldPreset`** — a shared geometry model: a flat list of tiles relative to a center anchor, with a single rotation transform used identically for live placement previews, custom "Custom Grid" generation, the built-in Standard Field, and saved custom slots. This keeps preview and commit guaranteed to match, and lets host-saved arbitrary shapes go through the exact same code path as built-in presets.

- **`GnomeballPanel`** — the Swing sidebar UI (`PluginPanel`): a "Connect" card (create/join) and an "In Game" card (scoreboard with inline team renaming/score correction, roster list with a host-only right-click role/ball-assignment menu, field-preset and zone-marking tools, host pre-start/in-game controls, referee controls, leave button).

- **Overlays** (`PlayerOverlay`, `TileOverlay`, `TimerOverlay`) — pure rendering, driven entirely by the plugin's current state:
  - `PlayerOverlay` draws jersey numbers/referee icon above players' heads, a ball indicator on the current holder, a pulsing "pass here" arrow on whoever owes a tag/delivery obligation, and a colored model outline for enlisted players standing on the field.
  - `TileOverlay` renders committed field/zone tiles (outlined as a connected region for Field/Zone A/Zone B, filled individually for plain "Standard" markers), plus live previews while a host is placing/removing a preset or drawing a zone, including an out-of-bounds pulse-flash.
  - `TimerOverlay` renders the clock/scoreboard box (top-left) plus full-screen flash banners for goals, whistle blows, interceptions, and host announcements.

- **`GnomeballConfig`** — user-facing toggles (show player overlay, show tile overlay) via RuneLite's standard config system.

### State & persistence

Session identity (active game ID, join code, write key, host RSN, phase, timer deadline) is saved to the RuneLite **per-profile** config so a game auto-resumes across logout/hop/relogin. Write keys for games this account has hosted are kept separately (bounded to the last 5), so leaving a game and rejoining it later restores host privileges without needing the original creation response. Saved custom field-preset slots are persisted as JSON via the same config manager.

### Client/server split in practice

Some interactions are detected purely client-side and then *reported* to the server (e.g. "I stepped into my zone while holding the ball," "I was just hit by an opposing player wielding a tag-eligible weapon") — the server decides whether they actually count and echoes back the authoritative event (`GOAL_SCORED`, `PLAYER_TAGGED`, `BALL_ASSIGNED`, etc.) for every connected client to apply. A few actions are shown with an optimistic local preview (e.g. a goal flash, a referee's pause) ahead of that echo purely for responsiveness, but the real, shared value is always whatever the server subsequently confirms.
