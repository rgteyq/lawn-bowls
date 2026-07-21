# LawnBowls

A [libGDX](https://libgdx.com/) project generated with [gdx-liftoff](https://github.com/libgdx/gdx-liftoff).

This project was generated with a template including simple application launchers and an `ApplicationAdapter` extension that draws libGDX logo.

## Platforms

- `core`: Main module with the application logic shared by all platforms.
- `lwjgl3`: Primary desktop platform using LWJGL3; was called 'desktop' in older docs.
- `android`: Android mobile platform. Needs Android SDK.

## Game loop

`io.github.lawn_bowls.Main` (in `core`, Java) is the `ApplicationAdapter` entry point shared by all
platforms. It owns one `AussieRulesEngine`, one `AussieBowlsPhysics`, one `Jack`, and an `Array<Bowl>`
of active bowls. Each `render()` call runs `updatePhysics(delta)` before drawing:

1. Advance the jack's `position` by its `velocity` (`Vector2.mulAdd`).
2. For each alive bowl: run `bowlsPhysics.update(bowl, delta)` to apply friction and draw/hook bias
   directly to the bowl's own `position`/`velocity`, then `rulesEngine.checkBowlToJackCollision(bowl, jack)`,
   then `rulesEngine.updateEntityBounds(bowl, jack)` — bounds/ditch/toucher checks always run
   immediately after a position update, never before.

Kotlin (`AussieRulesEngine`, `AussieBowlsPhysics`, `Bowl`, `Jack`) and Java (`Main`) sources live in
the same module and package tree and compile together; Kotlin's `isAlive`/`isToucher`/`isInDitch` `var`
properties are called from Java as `isAlive()`/`setAlive(...)`, etc.

The view is 3D, rendered with libGDX's `g3d` API (`PerspectiveCamera` + `ModelBatch` + `Environment`)
rather than the earlier 2D `ShapeRenderer`/`OrthographicCamera` top-down debug view. World units are
meters, matching `Bowl`/`Jack` positions directly — no pixels-per-meter scale is needed. Rink X (width,
`0..5`) maps straight to world X; rink Y (length, `0..~35.3`) maps to world Z as `-rinkY`, so the green
recedes into the screen along the camera's default forward (`-Z`) direction; world Y is height above
the green surface (`0`). `setUpCamera()` starts the `PerspectiveCamera` (65° FOV, `far = 250` to fit
the sky sphere and surrounding scenery — see **Scenery** below) at the idle view — behind and above the
mat (`idleCameraPosition = (2.5, 4.0, 9.0)`), looking up the length of the green
(`idleCameraLookAt = (2.5, 0.2, -16)`) — a broadcast-style angle rather than looking straight down. The
camera sits noticeably further back than the mat itself (rink Y `1.0`), pushing the mat up into the
upper-middle of the frame rather than the bottom edge — this leaves visible screen space *behind* the
mat for the slingshot-style pull-back drag (see **Aiming and delivery** below).

**Follow camera**: `updateCamera(delta)` (called every frame from `render()`, after physics/transforms
update) is a simple two-state camera — idle vs. tracking a released bowl — blended with exponential
smoothing so it never snaps:

- While the most recently released bowl (`trackedBowlIndex`, set in `releaseBowl()`) is still alive
  and moving (`velocity.len() > AussieBowlsPhysics.STOP_THRESHOLD`), the desired camera position
  trails `FOLLOW_BACK_DISTANCE_M` behind the bowl at `FOLLOW_HEIGHT_M`, looking `FOLLOW_LOOKAHEAD_M`
  ahead of it — but the *tracked* rink-Y is clamped to `AussieRulesEngine.GREEN_LENGTH / 2`, so the
  camera closes in on the action but stops advancing at the halfway mark rather than chasing the bowl
  all the way to the jack.
- Once the bowl stops or dies (or before any bowl has been delivered), the desired position/look-at
  fall back to the idle view.
- Every frame, `cameraPosition`/`cameraLookAt` are eased toward whichever is desired via
  `Vector3.lerp` with a framerate-independent factor (`1 - exp(-CAMERA_LERP_SPEED * delta)`), then
  applied to the real `camera` in `applyCamera()`. There's no separate "returning to idle" state to
  manage — the same lerp just pulls it back once the desired target flips back to idle.

`buildScene()` (called once from `create()`) builds all the geometry as `Model`s via `ModelBuilder`,
disposed together in `dispose()` (they're shared where possible — e.g. one `Model` per player's bowl
color, reused across every bowl that player delivers — rather than rebuilt per instance):

- **Grass**: a single box spanning the whole green, textured (not flat-colored) with
  `assets/bowling-green-tile2.jpeg` (a seamless square top-down tile, not a perspective photo) via
  `texturedBox()` — a `MeshPartBuilder` helper that sets a UV range beyond `[0,1]`
  (`setUVRange(0, 0, uRepeat, vRepeat)`) with the `Texture`'s wrap mode set to `Repeat`, so the image
  tiles every `GRASS_TILE_SIZE_M` (~2m) across the rink instead of stretching. There's no procedural
  stripe geometry anymore — the texture supplies the look directly. Two earlier candidate textures
  (`bowling-green-tile.jpeg`, `bowling-green-grass-background.jpg`) were tried and removed from
  `assets/` — lower quality than the one in use. One asset gotcha hit along the way: the first
  candidate file was actually a WebP image saved with a `.jpg` extension — libGDX's loader can't
  decode WebP, so it had to be re-encoded as a real JPEG before it would load (`file <path>` /
  `sips -g format` are the quick ways to catch this if a future asset swap silently fails the same way).
- **Ditch**: a box recessed `DITCH_RECESS_M` (15cm) below the green surface — a real sunken ditch,
  not just a differently-colored strip. There's one at *both* ends of the green (`rearDitch` beyond
  `GREEN_LENGTH`, `frontDitch` just short of `rinkY=0` behind the mat), sharing a single `ditchModel`
  — matching a real rink, where play alternates direction end to end so both ends need one. A thin
  `ditchWallModel` bank (`DITCH_WALL_THICKNESS_M`) closes the vertical gap between the green surface
  and the recessed floor right at each green/ditch seam — without it that gap was open space (you
  could see through to the ground/sky behind it), which read as a floating disconnected slab rather
  than a real ditch. Floor and wall use separate dark, low-reflectance colors (`DITCH_FLOOR_COLOR`,
  a charcoal grey standing in for synthetic ditch lining, and `DITCH_WALL_COLOR`, near-black as if in
  its own shadow) rather than the sand tone tried initially — sand read as a flat tan patch under
  simple lighting, whereas the dark floor/wall pairing contrasts hard against the bright grass and
  reads immediately as a recessed trough, with the wall-vs-floor shade difference adding a depth cue
  a single flat color couldn't give it.
- **Boundary lines**: thin, deliberately faint/low-contrast boxes (`BOUNDARY_LINE_COLOR`, a muted
  putty tone, not bright paint) along both long edges of the rink, standing in for the string/peg
  markers on a real rink. They span only the green itself (`GREEN_LENGTH`) and stop at the rink
  edge — they don't run on across the ditch. They're the *only* thing separating adjacent rinks —
  see Scenery below. Kept subtle on purpose: the corner poles are the real out-of-bounds signal, so
  the line shouldn't compete with them for attention.
- **Corner poles**: a white 2m-tall cylinder (`POLE_HEIGHT_M`/`POLE_DIAMETER_M`) at each of the
  rink's two *rear* corners only (both boundary lines x the rear ditch's outer wall) — the front
  corners, by the mat and closest to the camera, don't need one. One shared `poleModel` reused
  across both `ModelInstance`s.
- **Mat**: a thin cream box (`MAT_WIDTH_M` x `MAT_LENGTH_M`, `MAT_THICKNESS_M` ~1.5cm — realistic
  rubber-mat thickness) sitting on top of the green, built in `buildMat()`. Modeled after real
  Aero-style mats (referenced via a few product photos): two scattered clusters of small dark-green
  "diamond" studs — `addDiamondCluster()` places `DIAMOND_CLUSTER_OFFSETS` as unit boxes rotated 45°
  about Y so they read as diamonds — one `DIAMOND_CLUSTER_OFFSET_M` toward the front of the mat and
  one toward the rear, flanking a raised branding-panel placeholder in the middle. Real mats mark
  the centre implicitly through this symmetry rather than with a painted line, so there's no
  separate centre-line geometry; no footprint markings either, those didn't read as realistic.
- **Jack/bowls**: unit-radius sphere `Model`s (one shared per player color, `Color.MAROON` /
  `Color.NAVY`), scaled per-instance to the real (visually exaggerated) radius each frame in
  `updateInstanceTransforms()`. `Main` keeps a `bowlInstances` array index-aligned with `bowls` — a
  new `ModelInstance` is appended in `releaseBowl()` alongside each new `Bowl`.

Bowls are still player-driven, not seeded: click-drag-release from the mat to deliver one, slingshot
style — drag *behind* the mat (toward the camera) rather than toward the target; the bowl travels the
opposite way. `DeliveryInputAdapter` tracks the drag as `aiming`/`aimTarget`; `setAimTargetFromScreen()`
works by casting a pick ray from the `PerspectiveCamera` (`camera.getPickRay`) and intersecting it with
the ground plane (`Intersector.intersectRayPlane`) instead of a flat pixel-to-meter affine transform.

While aiming, `drawAimArrow()` draws a pull band from the mat back to the live drag point (not a
forward-pointing arrow — an arrowhead pointing opposite the bowl's actual travel direction read as
confusing) via `ShapeRenderer`, fed the 3D camera's projection matrix and 3D line coordinates.
`ShapeRenderer`'s filled `triangle()` has no 3D (x,y,z) overload, only `line()` does, so the "filled"
band and its round grip handle at the drag point are faked with bundles of 3D line strokes rather than
real triangles. The band's width, its grip handle's radius, and its color (a modest gold brightening to
a shimmering near-white yellow) all scale with the resulting release power, and `aimVisualDragDistance`
eases toward the real drag distance each frame (framerate-independent lerp, same pattern as the follow
camera) so the band visibly grows rather than snapping to length.

On release, `releaseBowl()` turns the drag into a new `Bowl`:

- **Direction** is the normalized vector from the release point back to the mat — the *opposite* of
  the drag vector (`pull`), matching the slingshot visual.
- **Speed** scales with drag distance — `MIN_RELEASE_SPEED` at no drag up to `MAX_RELEASE_SPEED` at
  `MAX_DRAG_DISTANCE_M` (3m; a short, comfortable pull rather than needing to drag the whole green's
  length).
- **Delivery hand** is derived, not chosen, from the *travel* direction (not the raw drag):
  `isBackhand = travelDir.x > 0f` — the bowl heading right is backhand (bias curves left), heading left
  is forehand (bias curves right). No key press needed. This matches the real technique of aiming wide
  on the side opposite your intended curve, so the bias arcs the bowl back in toward the jack rather
  than further away from it.
- **Owner** is the currently-up player from `End`; bowls render maroon for player 1, navy for player 2.

The drawn radius (`BOWL_VISUAL_RADIUS_SCALE`) is still exaggerated over the true physical scale for
visibility, same as the 2D view — this only affects rendering, collision/physics still use the real
`bowl.radius`/`jack.radius`. The Aero-style accent ring from the 2D view isn't carried over yet (no
built-in torus primitive in `ModelBuilder`); body color plus lighting/shading is the current
differentiation — a follow-up if it's still needed.

A top-left HUD (drawn with a `BitmapFont` at `0.8` scale via a 2D `SpriteBatch` pass after the 3D
scene, split across a few short lines to fit the narrow window) shows whose turn it is, how many bowls
each player has left, and `"End complete!"` once both have delivered their full allocation. A separate
scoreboard panel (top-centre) shows the running match score — see **Scoring** below.

## Scenery

`io.github.lawn_bowls.SceneryBuilder` (in `core`, Java, package-private) builds everything *outside*
the playable rink — ground, hedge, adjacent rinks, a clubhouse, a spectator area, and trees — so
`Main.buildScene()` stays focused on gameplay geometry. It's flat-colored/procedural throughout (no
new photographic textures, aside from reusing the rink's own grass texture on the two adjacent rink
strips), matching the existing mat/boundary style. It mirrors small pieces of `Main`'s own `box()` /
`texturedBox()` / `sphere()` helpers as private static methods rather than sharing a utility class —
consistent with `Main`'s own ad hoc, per-shape helper style rather than a premature abstraction.

- **`buildGroundAndHedge()`**: a large (`200m²`) flat ground plane, positioned just below the grass
  box so the rink doesn't float against open sky, plus a hedge perimeter (two long boxes) set back far
  enough from the rink centreline to clear the adjacent rinks below. `GROUND_Y` must clear the ditch
  floor (`Main.DITCH_RECESS_M + Main.DITCH_THICKNESS_M`), not just the grass underside — the ground
  box spans the whole scene footprint including under the ditch, so sitting any higher would bury it
  under solid ground (this was an actual bug: the ditch existed but was fully hidden until the ground
  was pushed below it).
- **`buildSurroundings()`**: two adjacent rink strips flanking the playable one (reusing `Main`'s grass
  `Texture` and tiling, not a new texture), each with its own front and rear ditch *and* bank wall
  matching the playable rink's own (same recess/thickness/color/wall-thickness, reused from `Main`'s
  package-private `DITCH_RECESS_M`/`DITCH_THICKNESS_M`/`DITCH_FLOOR_COLOR`/`DITCH_WALL_COLOR`/
  `DITCH_WALL_THICKNESS_M` so they
  can't drift out of sync), a simple clubhouse (wall box + roof box) beyond the ditch, a few spectator
  benches in front of it, and a handful of trunk-plus-foliage-sphere tree impostors scattered around
  the clubhouse/hedge perimeter.
  The adjacent strips sit flush against the playable rink (`ADJACENT_RINK_GAP_M = 0`) — no verge
  between rinks, so the faint boundary line is the only separation, matching a real green where rinks
  share no physical gap. Because the adjacent strips reuse the exact same grass `Texture`/tiling as
  the playable rink, they blend seamlessly into one continuous green rather than reading as a visibly
  separate patch — only the boundary line and corner poles mark where one rink ends and the next
  begins.

`Main.buildSky()` adds a large sphere (`SKY_RADIUS_M` = 200) surrounding the whole scene — `camera.far`
is `250` to comfortably contain it. It's lit fullbright regardless of the directional light's angle via
a black diffuse + emissive-color material (`gl_FragColor = diffuse * lighting + emissive`, so a black
diffuse zeroes out the lighting term and only the emissive color shows), with culling disabled
(`IntAttribute.createCullFace(GL20.GL_NONE)`) so its inward-facing surface renders from inside it —
without that, the sphere's front faces (visible from outside) would be back-face-culled from the
camera's position inside the sphere, and nothing would draw.

**Shadows**: bowls, jack, and the mat cast shadows onto the grass via a `DirectionalShadowLight`
(`environment.add(shadowLight)` *and* `environment.shadowMap = shadowLight` are both required — the
first adds it as a light source, the second is what the default shader actually checks to enable
shadow sampling). Grass, ditch, boundary lines, and all of `SceneryBuilder`'s geometry are deliberately
excluded from the shadow-casting `shadowCasters` array — the default shadow shader has no depth-bias
term, so large flat casters/receivers show acne at grazing angles. The shadow camera is re-centred each
frame on a fixed rink-centre point (not the moving follow-cam target), so the shadow frustum doesn't
jitter as the camera tracks a rolling bowl. The light's direction (`(-0.45, -0.75, -0.35)`, ~51° sun
elevation) is deliberately tilted well off vertical — a near-vertical light leaves shadows almost
entirely hidden under small objects like the jack, since the offset from directly beneath is what
makes a shadow visible at all.

One implementation pitfall worth knowing if this code gets touched again: `ModelBatch.render(...)`
only *queues* renderables — the actual GPU draw call happens in `flush()`, called from `end()`. Reading
the shadow map's framebuffer back (e.g. via `glReadPixels`, while debugging) before calling
`shadowBatch.end()` will show only whatever was already there (the FBO's own clear color), not what was
just rendered — this cost significant debugging time before the fix was traced to read-ordering, not
the shadow math itself.

## Turns

`io.github.lawn_bowls.game.End` (in `core`) tracks a single "end": two players (index 0 and 1)
alternate single deliveries until both have delivered `bowlsPerPlayer` bowls (default `4`, standard
singles). It doesn't touch `Bowl`/`Jack` directly — `Main` reads it to gate and tag deliveries:

- `currentPlayer` (read-only from outside) is whose turn it is; `recordDelivery()` increments that
  player's count and flips it to the other player.
- `bowlsRemaining(player)` is `bowlsPerPlayer` minus what they've delivered so far.
- `isComplete` is true once both players have delivered all their bowls.
- `canDeliver(bowls)` is the single gate for "is a new delivery allowed right now": false once the end
  is complete, or while any bowl passed in is still alive and moving (delegates to `allSettled`) —
  deliveries happen one at a time, never mid-roll. `Main.DeliveryInputAdapter.touchDown` checks this
  (via `Main.canDeliver()`, which also checks the match isn't over — see **Scoring**) before starting
  an aim, and `releaseBowl()` checks it again before committing the bowl, so bowls can't be fired while
  a previous one is still in flight.
- `allSettled(bowls)` is `canDeliver`'s "nothing still rolling" half, pulled out on its own because
  `Main` needs the same check separately: an end can be `isComplete` (all bowls delivered) while the
  very last one is still rolling, so scoring has to wait for `end.isComplete() && end.allSettled(bowls)`
  together, not `isComplete` alone.
- `startingPlayer` (constructor param, default `0`) records who opened the end — read back via
  `end.startingPlayer` when the next end starts, so the winner of an end can be handed the next one
  (or, for a void/replayed end, the same player can restart it). `currentPlayer` still starts there and
  moves independently as deliveries happen.

`Bowl.owner` (added alongside this) records which player delivered a given bowl — this is how "which
bowls are still in play" per player is tracked; it's set from `end.currentPlayer` at construction time
in `releaseBowl()`, before `recordDelivery()` advances the turn.

See `core/src/test/kotlin/io/github/lawn_bowls/game/EndTest.kt` for behavioral tests of the turn order,
remaining-bowl counts, completion, and delivery gating.

## Scoring

`io.github.lawn_bowls.game.Scoring` and `io.github.lawn_bowls.game.Match` (both in `core`, Kotlin) turn
a settled end into a shot count and accumulate that across a full match; `Main` drives the lifecycle
between them and renders the result as an on-screen scoreboard.

- **`Scoring.scoreEnd(bowls, jack)`** (a stateless `object`, `@JvmStatic` so `Main` can call it directly
  as `Scoring.scoreEnd(...)`) returns an `EndResult(winner, shots, isVoid)`. If the jack itself is dead
  (`!jack.isAlive` — knocked out of bounds per `AussieRulesEngine.checkHorizontalBounds`), the whole end
  is void and must be replayed rather than scored. Otherwise it filters to `bowl.isAlive` bowls only
  (this alone already gets touchers-in-the-ditch right and dead/out-of-bounds bowls wrong — no
  ditch-specific logic needed here, it's all encoded in `isAlive` upstream by the rules engine), sorts
  by distance to the jack (`Vector2.dst`), and counts consecutive bowls from the closest player's `owner`
  until the count is interrupted by the other player's nearest bowl — that count is `shots`.
- **`Match(maxEnds = 7)`** accumulates `scores` (an `IntArray(2)`) and `endsPlayed` via `recordEnd(result)`;
  a void result changes neither (so a replayed end doesn't count against the cap). `isComplete` is true
  once `endsPlayed >= maxEnds` *and* the scores actually differ — a tie at the cap keeps `isComplete`
  false, so the match keeps going end-by-end (still through ordinary `recordEnd` calls) until a
  tie-break end finally breaks it. `winner` is the higher-scoring player once `isComplete`, else `null`.

`Main` owns one `Match` alongside its `End`, and drives the handoff between ends in
`updateEndLifecycle(delta)` (called every frame, right after `updatePhysics`):

1. Once `end.isComplete() && end.allSettled(bowls)` and the just-finished end hasn't been scored yet,
   call `Scoring.scoreEnd`, feed the result to `match.recordEnd`, and start a short on-screen pause
   (`END_TRANSITION_DELAY_S`, 2.5s) showing a one-line result message (`describeResult`) so the final
   head is visible before anything resets.
2. Once that pause elapses, `startNextEnd(result)` clears `bowls`/`bowlInstances`, respots the jack at
   its original start position, and creates a new `End` — handed to `result.winner` (the real bowls
   convention: the end's winner bowls first next end), or to the *same* starter as before if the end was
   void (dead-jack replay) or scoreless. Rink-direction reversal between ends (a real detail) is
   deliberately not modeled — every end plays from the same mat position.
3. Once `match.isComplete()`, this whole cycle stops — the final green and scoreboard are left on
   screen, and `Main.canDeliver()` (which `touchDown`/`releaseBowl` both check instead of calling
   `end.canDeliver` directly) starts returning `false` so no further deliveries are accepted.

**Scoreboard rendering**: `drawScoreboardPanels()` (a `ShapeRenderer` filled-rect pass, screen-space —
projection matrix borrowed from `batch.getProjectionMatrix()` rather than the 3D camera used for
`drawAimArrow`) draws two adjacent boxes colored `player0Color`/`player1Color`; `drawScoreboardText()`
(called from the same `batch.begin()/end()` block as `drawHud`) overlays each player's score (a second,
larger-scaled `BitmapFont`, `scoreFont`), a "v" divider, an ends counter (`"End n / 7"`, or
`"Tie-break end n"` past the cap), and the transient result message from step 1 above, or the final
`"Player N wins X–Y"` line once the match is complete. The two-panels-plus-"v" layout is styled after a
real portable lawn-bowls A-frame scoreboard (`assets/score_board.avif`, kept as a design reference, not
loaded as a texture) but recolored to the game's own `player0Color`/`player1Color` (maroon/navy) instead
of that photo's colors, and without reproducing its printed branding.

See `core/src/test/kotlin/io/github/lawn_bowls/game/ScoringTest.kt` and `MatchTest.kt` for behavioral
tests of shot-counting (including the toucher-in-ditch and dead-bowl-excluded cases) and match
accumulation/tie-break behavior.

## Physics

`io.github.lawn_bowls.physics.AussieBowlsPhysics` (in `core`) models friction and draw/hook bias on a
standard Australian rink (5.0m wide x 35.0m long), tuned for a "15 second green" pace. It can be
driven two ways:

- **Standalone simulation**: call `release(start, direction, speed)` once, then `update(deltaTime)`
  every frame; read back the engine's own `position`, `velocity`, and `isMoving`. Useful for previewing
  a trajectory before it's committed to a live `Bowl`.
- **Live entity**: call `update(bowl: Bowl, deltaTime: Float)` every frame — it mutates `bowl.position`
  and `bowl.velocity` in place, using `bowl.initialSpeed` for the speed-ratio calculation and
  `bowl.isBackhand` for bias direction. This is what `Main.updatePhysics()` calls per bowl. A dead bowl
  (`isAlive = false`) is left untouched.

Both paths share the same physics step:

- **Friction**: linear ground deceleration calibrated so a bowl at `REFERENCE_RELEASE_SPEED` comes to
  rest in `TARGET_STOP_TIME_SECONDS` (15s).
- **Draw/hook bias**: a sideways force applied perpendicular to the bowl's heading each update —
  backhand pulls left of travel, forehand pulls right. It stays constant (`BASE_BIAS_FORCE`) while the
  bowl is above `HOOK_TRIGGER_RATIO` of its release speed, then ramps up exponentially below that
  threshold, producing the late "hook" into the jack as the bowl loses pace.
  `TAPER_FLOOR_SPEED` scales bias magnitude by `(speed / TAPER_FLOOR_SPEED).coerceAtMost(1f)`, fading
  it to zero as the bowl nears a dead stop — without this, a strong-enough bias re-injects sideways
  speed via the exponential ramp faster than friction removes it, and the bowl never actually stops
  (it didn't, repeatedly, while tuning this). The curve is unaffected everywhere except the last
  fraction of a second of roll, where it's already imperceptible anyway.
- **Where the hook starts, by distance, isn't the same as by speed**: under constant deceleration,
  speed ratio and distance traveled aren't proportional — with `s` = fraction of total roll *time*
  elapsed, `distance-fraction = 2s - s²`. The original `HOOK_TRIGGER_RATIO` of `0.40` (speed ratio)
  corresponds to `s=0.6`, i.e. only 84% of the way down the rink — it read as "only kicks in right at
  the end" rather than "from halfway." `HOOK_TRIGGER_RATIO = 0.70` (solving `2s - s² = 0.5` for
  `s ≈ 0.293`, giving `speedRatio ≈ 0.71`) puts the visible turn at roughly the rink's halfway point
  instead. Raising the trigger this much widens the ramp-active window enough on its own that
  `BASE_BIAS_FORCE`/`HOOK_EXPONENT` didn't need to increase too — total sideways drift over a full
  roll is already ~60% more than the original `0.40`/`0.015`/`3.5` tuning, just correctly positioned
  starting from halfway instead of concentrated in the last 16% of the roll.

See `core/src/test/kotlin/io/github/lawn_bowls/physics/AussieBowlsPhysicsTest.kt` for behavioral tests
of both the standalone and `Bowl`-driven paths.

## Model

`io.github.lawn_bowls.model` (in `core`) holds plain data classes for the objects on the green, each
with a LibGDX `Vector2` `position` and `velocity`:

- **`Bowl`**: `radius` (default `0.065f`, a standard 130mm Aussie bowl), `isBackhand` (delivery hand,
  default `false`/forehand), `initialSpeed` (release speed in m/s, default `0.0f`), `owner` (which
  player delivered it, default `0`), `isAlive`, `isToucher`, `isInDitch`.
- **`Jack`**: `radius` (default `0.0315f`, a 63mm jack), `isAlive`, `isInDitch`.

`isAlive`, `isToucher`, and `isInDitch` are `var` (not `val`) so rules code such as
`AussieRulesEngine` can flip them in place as the game state evolves; `radius`, `isBackhand`,
`initialSpeed`, `owner`, and the `Vector2` references are fixed per bowl, though the vectors' own
`x`/`y` remain mutable.

## Rules

`io.github.lawn_bowls.rules.AussieRulesEngine` (in `core`) applies Bowls Australia boundary laws for
a standard rink, 1 unit = 1 meter: width spans `[0.0, 5.0]`, the green surface spans `[0, 35.0]`, and
the ditch adds `0.3` (300mm) beyond the green at the far end, up to the back wall at `35.3`.

`updateEntityBounds(bowl: Bowl, jack: Jack)` mutates both entities in place each update:

- **Horizontal boundary**: if the jack or the bowl crosses `x <= 0.0` or `x >= 5.0`, it's flagged
  dead (`isAlive = false`).
- **Jack in the ditch**: stays alive; once it reaches the back wall its position is clamped there and
  its velocity is zeroed.
- **Bowl in the ditch**: if `isToucher` is true, it behaves like the jack (stays alive, stops at the
  back wall); otherwise it's flagged dead and its velocity is zeroed immediately.

`checkBowlToJackCollision(bowl: Bowl, jack: Jack)` detects circle-to-circle contact via
`Vector2.dst()` against the combined `radius` of both entities, and only resolves it while a moving
bowl is still closing in (skips already-separating pairs). The response is a basic equal-mass elastic
collision — velocity components along the collision normal are swapped between bowl and jack,
tangential components are untouched. If the hit happens while both are still on the green (not in the
ditch) and the bowl isn't already a toucher, it sets `bowl.isToucher = true` and prints a console
message.

## Gradle

This project uses [Gradle](https://gradle.org/) to manage dependencies.
The Gradle wrapper was included, so you can run Gradle tasks using `gradlew.bat` or `./gradlew` commands.
Useful Gradle tasks and flags:

- `--continue`: when using this flag, errors will not stop the tasks from running.
- `--daemon`: thanks to this flag, Gradle daemon will be used to run chosen tasks.
- `--offline`: when using this flag, cached dependency archives will be used.
- `--refresh-dependencies`: this flag forces validation of all dependencies. Useful for snapshot versions.
- `android:lint`: performs Android project validation.
- `build`: builds sources and archives of every project.
- `cleanEclipse`: removes Eclipse project data.
- `cleanIdea`: removes IntelliJ project data.
- `clean`: removes `build` folders, which store compiled classes and built archives.
- `eclipse`: generates Eclipse project data.
- `idea`: generates IntelliJ project data.
- `lwjgl3:jar`: builds application's runnable jar, which can be found at `lwjgl3/build/libs`.
- `lwjgl3:run`: starts the application.
- `test`: runs unit tests (if any).

Note that most tasks that are not specific to a single project can be run with `name:` prefix, where the `name` should be replaced with the ID of a specific project.
For example, `core:clean` removes `build` folder only from the `core` project.


