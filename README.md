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
the green surface (`0`). `setUpCamera()` starts the `PerspectiveCamera` (65° FOV) at the idle view —
behind and above the mat (`idleCameraPosition = (2.5, 4.0, 6.0)`), looking up the length of the green
(`idleCameraLookAt = (2.5, 0.2, -20)`) — a broadcast-style angle rather than looking straight down.
The camera sits noticeably further back than the mat itself (rink Y `1.0`) — with less clearance the
mat sat right at the edge of the vertical field of view and got clipped.

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
  stripe geometry anymore — the texture supplies the look directly. `assets/bowling-green-tile.jpeg`
  and `assets/bowling-green-grass-background.jpg` (earlier candidates) are unused now but still in
  the repo if you want to remove them. One asset gotcha hit along the way: the first candidate file
  was actually a WebP image saved with a `.jpg` extension — libGDX's loader can't decode WebP, so it
  had to be re-encoded as a real JPEG before it would load (`file <path>` / `sips -g format` are the
  quick ways to catch this if a future asset
  swap silently fails the same way).
- **Ditch**: a dark box recessed `DITCH_RECESS_M` (15cm) below the green surface — a real sunken
  ditch, not just a differently-colored strip.
- **Boundary lines**: thin white boxes along both long edges of the rink, standing in for the
  string/peg markers on a real rink.
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

Bowls are still player-driven, not seeded: click-drag-release from the mat to deliver one.
`DeliveryInputAdapter` tracks the drag as `aiming`/`aimTarget`; `setAimTargetFromScreen()` now works by
casting a pick ray from the `PerspectiveCamera` (`camera.getPickRay`) and intersecting it with the
ground plane (`Intersector.intersectRayPlane`) instead of a flat pixel-to-meter affine transform — the
aim line itself is still drawn with `ShapeRenderer`, just fed the 3D camera's projection matrix and 3D
line coordinates. On release, `releaseBowl()` turns the drag into a new `Bowl`:

- **Direction** is the normalized vector from the mat to the release point.
- **Speed** scales with drag distance — `MIN_RELEASE_SPEED` at the mat up to `MAX_RELEASE_SPEED` at a
  full drag to the far ditch line (`SPEED_PER_METER_OF_DRAG = (MAX - MIN) / AussieRulesEngine.GREEN_LENGTH`).
- **Delivery hand** is derived, not chosen: `isBackhand = offset.x > 0f` — dragging right of the
  mat's centre line is backhand (bias curves left), dragging left is forehand (bias curves right).
  No key press needed. This matches the real technique of aiming wide on the side opposite your
  intended curve, so the bias arcs the bowl back in toward the jack rather than further away from it.
- **Owner** is the currently-up player from `End`; bowls render maroon for player 1, navy for player 2.

The drawn radius (`BOWL_VISUAL_RADIUS_SCALE`) is still exaggerated over the true physical scale for
visibility, same as the 2D view — this only affects rendering, collision/physics still use the real
`bowl.radius`/`jack.radius`. The Aero-style accent ring from the 2D view isn't carried over yet (no
built-in torus primitive in `ModelBuilder`); body color plus lighting/shading is the current
differentiation — a follow-up if it's still needed.

A top-left HUD (drawn with a `BitmapFont` at `0.8` scale via a 2D `SpriteBatch` pass after the 3D
scene, split across a few short lines to fit the narrow window) shows whose turn it is, how many bowls
each player has left, and `"End complete!"` once both have delivered their full allocation.

## Turns

`io.github.lawn_bowls.game.End` (in `core`) tracks a single "end": two players (index 0 and 1)
alternate single deliveries until both have delivered `bowlsPerPlayer` bowls (default `4`, standard
singles). It doesn't touch `Bowl`/`Jack` directly — `Main` reads it to gate and tag deliveries:

- `currentPlayer` (read-only from outside) is whose turn it is; `recordDelivery()` increments that
  player's count and flips it to the other player.
- `bowlsRemaining(player)` is `bowlsPerPlayer` minus what they've delivered so far.
- `isComplete` is true once both players have delivered all their bowls.
- `canDeliver(bowls)` is the single gate for "is a new delivery allowed right now": false once the end
  is complete, or while any bowl passed in is still alive and moving (`velocity` non-zero) — deliveries
  happen one at a time, never mid-roll. `Main.DeliveryInputAdapter.touchDown` checks this before
  starting an aim, and `releaseBowl()` checks it again before committing the bowl, so bowls can't be
  fired while a previous one is still in flight.

`Bowl.owner` (added alongside this) records which player delivered a given bowl — this is how "which
bowls are still in play" per player is tracked; it's set from `end.currentPlayer` at construction time
in `releaseBowl()`, before `recordDelivery()` advances the turn.

See `core/src/test/kotlin/io/github/lawn_bowls/game/EndTest.kt` for behavioral tests of the turn order,
remaining-bowl counts, completion, and delivery gating.

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


