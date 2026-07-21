package io.github.lawn_bowls;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.IntAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalShadowLight;
import com.badlogic.gdx.graphics.g3d.utils.DepthShaderProvider;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Intersector;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Plane;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import io.github.lawn_bowls.ai.BowlAi;
import io.github.lawn_bowls.ai.DeliveryPlan;
import io.github.lawn_bowls.game.End;
import io.github.lawn_bowls.game.EndResult;
import io.github.lawn_bowls.game.Match;
import io.github.lawn_bowls.game.Scoring;
import io.github.lawn_bowls.model.Bowl;
import io.github.lawn_bowls.model.Jack;
import io.github.lawn_bowls.physics.AussieBowlsPhysics;
import io.github.lawn_bowls.rules.AussieRulesEngine;

/**
 * {@link com.badlogic.gdx.ApplicationListener} implementation shared by all platforms.
 *
 * <p>World units are meters, matching {@link Bowl}/{@link Jack} positions directly — no separate
 * pixels-per-meter scale is needed in 3D. Rink X (width, 0..5) maps straight to world X; rink Y
 * (length, 0..~35.3) maps to world Z as {@code -rinkY}, so the green recedes into the screen along
 * the camera's default forward (-Z) direction. World Y is height above the green surface (0).
 */
public class Main extends ApplicationAdapter {
    private static final String GRASS_TEXTURE_FILE = "bowling-green-tile2.jpeg";
    private static final float GRASS_TILE_SIZE_M = 2.0f; // one texture repeat per ~2m of rink
    private static final float GREEN_THICKNESS_M = 0.02f;
    // Package-private: SceneryBuilder reuses these so the adjacent rinks' ditches (and the ground
    // plane that must clear all of them) match the playable rink's own exactly.
    static final float DITCH_RECESS_M = 0.15f; // ditch floor sits below the green surface
    static final float DITCH_THICKNESS_M = 0.10f;
    // Dark synthetic ditch lining (rubber/carpet, as on a real green) for the floor — not visible
    // sand, a flat tan patch didn't read as a recess under simple lighting, whereas a dark,
    // low-reflectance material contrasts hard against the bright grass and immediately reads as a
    // trough. The wall, though, is a grassed bank in reality (reference: assets/back_ditch.jpeg —
    // the green turf runs right up to and down into the ditch, no dark seam) — an earlier near-black
    // wall color read as an artificial trench rather than a turfed drop, so it's a grass tone instead,
    // a shade darker than the flat green surface as if in its own shadow (a real depth cue, not just
    // a color match).
    static final Color DITCH_FLOOR_COLOR = new Color(0.16f, 0.16f, 0.18f, 1f);
    static final Color DITCH_WALL_COLOR = new Color(0.18f, 0.38f, 0.14f, 1f);
    // A thin bank wall closing the vertical gap between the green surface and the recessed ditch
    // floor right below it — without this, that gap is open space, showing whatever's behind it
    // (ground/sky) rather than reading as a connected ditch.
    static final float DITCH_WALL_THICKNESS_M = 0.02f;
    private static final float BOUNDARY_LINE_WIDTH_M = 0.03f;
    private static final float BOUNDARY_LINE_HEIGHT_M = 0.02f;
    // Deliberately faint/low-contrast against the grass rather than a bright painted line — the
    // corner poles are the real out-of-bounds signal, this is just a subtle on-ground guide that
    // shouldn't compete with them for attention.
    private static final Color BOUNDARY_LINE_COLOR = new Color(0.58f, 0.62f, 0.50f, 1f);

    private static final float POLE_HEIGHT_M = 2.0f;
    private static final float POLE_DIAMETER_M = 0.08f;

    // Short alternating white/blue ditch markers along the rear ditch's inner edge (reference:
    // assets/back_ditch.jpeg, labeled "Ditch Markers" there) — a distinct real feature from the
    // tall corner poles above: low pegs spaced across the ditch rather than tall posts at its corners.
    private static final float DITCH_MARKER_HEIGHT_M = 0.3f;
    private static final float DITCH_MARKER_DIAMETER_M = 0.03f;
    private static final int DITCH_MARKER_COUNT = 5;
    private static final Color DITCH_MARKER_BLUE = new Color(0.15f, 0.25f, 0.55f, 1f);

    private static final float SKY_RADIUS_M = 200f;
    private static final String CLOUDS_TEXTURE_FILE = "clouds.jpeg";
    // Repeated rather than stretched once across the whole sphere — a single copy of a small photo
    // spread over a 200m sphere would be a blurry smear; tiling several times reads as a believable
    // cloud pattern instead. Not seamless at the tile edges, but the source photo's soft, irregular
    // clouds hide the repeat far better than a sharp/geometric texture would.
    private static final float SKY_CLOUD_U_REPEAT = 6f;
    private static final float SKY_CLOUD_V_REPEAT = 3f;

    private static final float MAT_WIDTH_M = 0.36f;
    private static final float MAT_LENGTH_M = 0.61f;
    private static final float MAT_THICKNESS_M = 0.015f; // a real rubber mat is thin, ~1-1.5cm

    // Two scattered clusters of diamond-shaped studs (rotated squares), one toward the front of
    // the mat and one toward the rear, flanking the branding panel — matches real Aero-style mats,
    // which mark the centre implicitly via this symmetry rather than with a painted line.
    private static final float DIAMOND_SIZE_M = 0.035f;
    private static final float DIAMOND_THICKNESS_M = 0.004f;
    private static final float DIAMOND_CLUSTER_OFFSET_M = MAT_LENGTH_M * 0.28f; // from mat centre
    private static final float[][] DIAMOND_CLUSTER_OFFSETS = {
        {-0.10f, -0.05f}, {-0.03f, -0.08f}, {0.05f, -0.05f}, {0.11f, -0.08f},
        {-0.07f, 0.03f}, {0.02f, 0.06f}, {0.09f, 0.02f}
    };

    // Bowls/jack are only 13cm/6.3cm across on a 5m-wide rink; exaggerate the drawn radius for
    // visibility. Collision/physics still use the real Bowl.radius/Jack.radius. Both use the same
    // scale (the jack previously got its own larger one) so the jack still reads as smaller than a
    // bowl on screen, matching its real ~63mm vs ~130mm proportions — a bigger jack-only scale made
    // it look larger than the bowls, which is backwards.
    private static final float VISUAL_RADIUS_SCALE = 1.6f;

    private static final float MIN_RELEASE_SPEED = 1.0f;
    private static final float MAX_RELEASE_SPEED = 3.5f;
    // Slingshot-style pull: dragging this far behind the mat charges full power. Kept short so a
    // full-power delivery only needs a small, comfortable pull rather than the whole green.
    private static final float MAX_DRAG_DISTANCE_M = 3.0f;
    private static final float SPEED_PER_METER_OF_DRAG =
        (MAX_RELEASE_SPEED - MIN_RELEASE_SPEED) / MAX_DRAG_DISTANCE_M;

    // Follow-cam: while the tracked bowl is moving, the camera closes in on it, but never tracks
    // past halfway up the green — it eases to a stop there instead of following all the way to
    // the jack. Once the bowl stops or dies, the camera eases back out to the idle view.
    private static final float FOLLOW_HEIGHT_M = 2.0f;
    private static final float FOLLOW_BACK_DISTANCE_M = 2.5f;
    private static final float FOLLOW_LOOKAHEAD_M = 6.0f;
    private static final float CAMERA_LERP_SPEED = 3.0f;

    // Scoreboard HUD: two colour-coded panels (one per player) split by a "v", with an ends
    // counter below — styled after a real portable lawn-bowls A-frame scoreboard, drawn top-centre
    // with the same ShapeRenderer/SpriteBatch/BitmapFont already used for the rest of the HUD.
    private static final float SCOREBOARD_PANEL_WIDTH = 70f;
    private static final float SCOREBOARD_PANEL_HEIGHT = 60f;
    private static final float SCOREBOARD_PANEL_GAP = 26f;
    private static final float SCOREBOARD_TOP_MARGIN = 8f;
    // How long the "Player N scores M" message lingers, and the green stays put, before the next
    // end's bowls/jack are auto-reset — long enough to read the final head.
    private static final float END_TRANSITION_DELAY_S = 2.5f;

    // Player 2 (navy) is always the computer opponent — see BowlAi. A brief pause before it
    // delivers (AI_THINK_DELAY_S) so it doesn't fire the instant it's up; purely for pacing/feel,
    // not a technical requirement.
    private static final int AI_PLAYER = 1;
    private static final float AI_THINK_DELAY_S = 1.0f;
    private static final float AI_DIFFICULTY = 0.35f; // 0 = perfect, 1 = heavy jitter

    private SpriteBatch batch;
    private BitmapFont font;
    private BitmapFont scoreFont;
    private GlyphLayout layout;
    private ShapeRenderer shapeRenderer;
    private Texture grassTexture;
    private Texture cloudsTexture;

    private PerspectiveCamera camera;
    private ModelBatch modelBatch;
    private Environment environment;

    // Shadow pass: only the mat/jack/live bowls cast (see drawShadows()) — the default shadow
    // shader has no depth-bias term, so large flat casters/receivers (grass, ditch, scenery) show
    // acne at grazing angles if included as casters.
    private DirectionalShadowLight shadowLight;
    private ModelBatch shadowBatch;
    private final Array<ModelInstance> shadowCasters = new Array<>();
    // Fixed at rink centre (not the moving follow-cam target) so the shadow frustum doesn't jitter.
    private final Vector3 shadowCenter = new Vector3(
        AussieBowlsPhysics.RINK_WIDTH_M / 2f, 0f, -AussieRulesEngine.GREEN_LENGTH / 2f
    );

    // Reusable geometry, built once in create() and disposed in dispose().
    private final Array<Model> ownedModels = new Array<>();
    private final Array<ModelInstance> staticInstances = new Array<>();
    private Model jackModel;
    private Model bowlModelPlayer0;
    private Model bowlModelPlayer1;

    private ModelInstance matInstance;
    private ModelInstance jackInstance;
    // Index-aligned with `bowls`: bowlInstances.get(i) renders bowls.get(i).
    private final Array<ModelInstance> bowlInstances = new Array<>();

    private AussieRulesEngine rulesEngine;
    private AussieBowlsPhysics bowlsPhysics;
    private BowlAi bowlAi;
    private End end;
    private Match match;
    private Jack jack;
    private Array<Bowl> bowls;

    // Counts up while it's AI_PLAYER's turn and the green has settled; once it passes
    // AI_THINK_DELAY_S the AI actually delivers (see updateAiTurn()).
    private float aiThinkTimer;

    // End-to-end lifecycle: set once the just-completed end has been scored, cleared again once
    // the next end's bowls/jack are reset. lastEndMessage/endTransitionTimer drive the on-screen
    // pause (see END_TRANSITION_DELAY_S) between a scored end and the green resetting.
    private boolean endResolved = false;
    private EndResult pendingResult;
    private String lastEndMessage = "";
    private float endTransitionTimer;

    private final Vector2 deliveryOrigin = new Vector2(AussieBowlsPhysics.RINK_WIDTH_M / 2f, 1.0f);
    private final Vector2 aimTarget = new Vector2();
    private boolean aiming = false;
    private float aimAnimTime = 0f;
    // Eased toward the real drag distance each frame so the arrow visibly grows rather than snapping.
    private float aimVisualDragDistance = 0f;

    private final Plane groundPlane = new Plane(Vector3.Y, 0f);
    private final Vector3 pickResult = new Vector3();

    // Pulled back and angled down further than the mat's own position (rinkY=1) so the mat sits in
    // the upper-middle of the view rather than hugging the bottom edge — leaving room below it on
    // screen for the slingshot-style pull-back drag (see DeliveryInputAdapter/releaseBowl). Closer
    // and shallower than the very first idle framing, which left a large dead patch of empty grass
    // below the mat with nothing happening in it.
    private final Vector3 idleCameraPosition = new Vector3(2.5f, 3.5f, 7.5f);
    private final Vector3 idleCameraLookAt = new Vector3(2.5f, 0.45f, -14f);
    private final Vector3 cameraPosition = new Vector3();
    private final Vector3 cameraLookAt = new Vector3();
    private final Vector3 desiredCameraPosition = new Vector3();
    private final Vector3 desiredCameraLookAt = new Vector3();
    private int trackedBowlIndex = -1;

    // Aero-style bowls: a dark solid body with a bright contrasting identification band, rather
    // than a single flat color.
    private final Color player0Color = Color.MAROON.cpy();
    private final Color player1Color = Color.NAVY.cpy();


    @Override
    public void create() {
        batch = new SpriteBatch();
        font = new BitmapFont();
        font.getData().setScale(0.8f);
        scoreFont = new BitmapFont();
        scoreFont.getData().setScale(2.4f);
        layout = new GlyphLayout();
        shapeRenderer = new ShapeRenderer();

        rulesEngine = new AussieRulesEngine();
        bowlsPhysics = new AussieBowlsPhysics();
        bowlAi = new BowlAi(bowlsPhysics, rulesEngine, MIN_RELEASE_SPEED, MAX_RELEASE_SPEED, AI_DIFFICULTY);
        end = new End();
        match = new Match();
        jack = new Jack();
        bowls = new Array<>();
        jack.getPosition().set(2.5f, 24f);

        setUpCamera();
        setUpEnvironment();
        buildScene();

        Gdx.input.setInputProcessor(new DeliveryInputAdapter());
    }

    private void setUpCamera() {
        camera = new PerspectiveCamera(65f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 0.1f;
        // Far enough to comfortably contain the sky sphere and surrounding scenery without clipping.
        camera.far = 250f;
        // Start at the idle view: a little behind and above the mat, looking up the whole green.
        cameraPosition.set(idleCameraPosition);
        cameraLookAt.set(idleCameraLookAt);
        applyCamera();
    }

    private void applyCamera() {
        camera.position.set(cameraPosition);
        camera.up.set(Vector3.Y);
        camera.lookAt(cameraLookAt);
        camera.update();
    }

    private void setUpEnvironment() {
        environment = new Environment();
        environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.55f, 0.55f, 0.55f, 1f));
        // 40x40 viewport covers the ~5m x 36m play area with margin for the follow-cam getting
        // close; near/far kept tight around the actual rink bounds (not camera.far) to preserve
        // shadow-map depth precision. Both add() and shadowMap= are required — add() alone doesn't
        // wire up shadow sampling in the default shader.
        shadowLight = new DirectionalShadowLight(2048, 2048, 40f, 40f, 1f, 60f);
        // ~51 degree sun elevation: a near-vertical angle leaves shadows almost entirely hidden
        // under small objects like the jack/bowls, so this is tilted enough to cast a visible,
        // grounding shadow while still reading as a natural midday sun rather than a sunset.
        shadowLight.set(0.8f, 0.8f, 0.75f, -0.45f, -0.75f, -0.35f);
        environment.add(shadowLight);
        environment.shadowMap = shadowLight;
        shadowBatch = new ModelBatch(new DepthShaderProvider());
    }

    /** Builds the static geometry (grass, ditch, boundary lines, mat) and the reusable entity models. */
    private void buildScene() {
        modelBatch = new ModelBatch();
        ModelBuilder builder = new ModelBuilder();
        long attrs = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;

        cloudsTexture = new Texture(Gdx.files.internal(CLOUDS_TEXTURE_FILE));
        cloudsTexture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        buildSky(builder, attrs);
        SceneryBuilder.buildGroundAndHedge(builder, attrs, staticInstances, ownedModels);

        grassTexture = new Texture(Gdx.files.internal(GRASS_TEXTURE_FILE));
        grassTexture.setWrap(Texture.TextureWrap.Repeat, Texture.TextureWrap.Repeat);
        float grassURepeat = AussieBowlsPhysics.RINK_WIDTH_M / GRASS_TILE_SIZE_M;
        float grassVRepeat = AussieRulesEngine.GREEN_LENGTH / GRASS_TILE_SIZE_M;
        Model grassModel = texturedBox(
            builder, AussieBowlsPhysics.RINK_WIDTH_M, GREEN_THICKNESS_M, AussieRulesEngine.GREEN_LENGTH,
            grassTexture, grassURepeat, grassVRepeat, attrs
        );
        ModelInstance grass = new ModelInstance(grassModel);
        grass.transform.setToTranslation(
            AussieBowlsPhysics.RINK_WIDTH_M / 2f, -GREEN_THICKNESS_M / 2f, -AussieRulesEngine.GREEN_LENGTH / 2f
        );
        staticInstances.add(grass);

        // Grass only — the adjacent rinks' own ditches are separate sand-colored boxes, not part of
        // this texture, matching how the playable rink's grass and ditch are modeled separately.
        float adjacentRinkVRepeat = AussieRulesEngine.GREEN_LENGTH / GRASS_TILE_SIZE_M;
        SceneryBuilder.buildSurroundings(
            builder, attrs, grassTexture, grassURepeat, adjacentRinkVRepeat, staticInstances, ownedModels
        );

        // A ditch at both ends of the green, matching a real rink (play alternates direction end to
        // end, so both ends need one) — the rear ditch beyond GREEN_LENGTH, and a mirrored front
        // ditch just short of rinkY=0, behind the mat.
        Model ditchModel = box(builder, AussieBowlsPhysics.RINK_WIDTH_M, DITCH_THICKNESS_M, AussieRulesEngine.DITCH_DEPTH, DITCH_FLOOR_COLOR, attrs);
        ModelInstance rearDitch = new ModelInstance(ditchModel);
        rearDitch.transform.setToTranslation(
            AussieBowlsPhysics.RINK_WIDTH_M / 2f,
            -DITCH_RECESS_M - DITCH_THICKNESS_M / 2f,
            -(AussieRulesEngine.GREEN_LENGTH + AussieRulesEngine.DITCH_DEPTH / 2f)
        );
        staticInstances.add(rearDitch);
        ModelInstance frontDitch = new ModelInstance(ditchModel);
        frontDitch.transform.setToTranslation(
            AussieBowlsPhysics.RINK_WIDTH_M / 2f,
            -DITCH_RECESS_M - DITCH_THICKNESS_M / 2f,
            AussieRulesEngine.DITCH_DEPTH / 2f
        );
        staticInstances.add(frontDitch);

        // Bank walls closing the vertical gap between the green surface and each ditch floor, right
        // at the green/ditch seam (front at rinkY=0, rear at rinkY=GREEN_LENGTH).
        Model ditchWallModel = box(
            builder, AussieBowlsPhysics.RINK_WIDTH_M, DITCH_RECESS_M, DITCH_WALL_THICKNESS_M, DITCH_WALL_COLOR, attrs
        );
        ModelInstance frontDitchWall = new ModelInstance(ditchWallModel);
        frontDitchWall.transform.setToTranslation(AussieBowlsPhysics.RINK_WIDTH_M / 2f, -DITCH_RECESS_M / 2f, 0f);
        staticInstances.add(frontDitchWall);
        ModelInstance rearDitchWall = new ModelInstance(ditchWallModel);
        rearDitchWall.transform.setToTranslation(
            AussieBowlsPhysics.RINK_WIDTH_M / 2f, -DITCH_RECESS_M / 2f, -AussieRulesEngine.GREEN_LENGTH
        );
        staticInstances.add(rearDitchWall);

        float backOuterZ = -(AussieRulesEngine.GREEN_LENGTH + AussieRulesEngine.DITCH_DEPTH);

        // Rink boundary markers along both long edges, spanning only the green itself — they stop at
        // the rink edge rather than running on across the ditch.
        float boundaryCenterZ = -AussieRulesEngine.GREEN_LENGTH / 2f;
        Model boundaryModel = box(
            builder, BOUNDARY_LINE_WIDTH_M, BOUNDARY_LINE_HEIGHT_M, AussieRulesEngine.GREEN_LENGTH, BOUNDARY_LINE_COLOR, attrs
        );
        ModelInstance leftBoundary = new ModelInstance(boundaryModel);
        leftBoundary.transform.setToTranslation(BOUNDARY_LINE_WIDTH_M / 2f, BOUNDARY_LINE_HEIGHT_M / 2f, boundaryCenterZ);
        staticInstances.add(leftBoundary);
        ModelInstance rightBoundary = new ModelInstance(boundaryModel);
        rightBoundary.transform.setToTranslation(
            AussieBowlsPhysics.RINK_WIDTH_M - BOUNDARY_LINE_WIDTH_M / 2f, BOUNDARY_LINE_HEIGHT_M / 2f, boundaryCenterZ
        );
        staticInstances.add(rightBoundary);

        // White out-of-bounds poles at the rink's two rear corners only — the front corners (by the
        // mat, closest to the camera) don't need one.
        Model poleModel = builder.createCylinder(
            POLE_DIAMETER_M, POLE_HEIGHT_M, POLE_DIAMETER_M, 12, new Material(ColorAttribute.createDiffuse(Color.WHITE)), attrs
        );
        ownedModels.add(poleModel);
        float[] poleXs = {0f, AussieBowlsPhysics.RINK_WIDTH_M};
        for (float poleX : poleXs) {
            ModelInstance pole = new ModelInstance(poleModel);
            pole.transform.setToTranslation(poleX, POLE_HEIGHT_M / 2f, backOuterZ);
            staticInstances.add(pole);
        }

        // Short alternating white/blue ditch markers spaced across the rear ditch's inner edge (the
        // green/ditch seam, same rinkY as rearDitchWall above) — see DITCH_MARKER_* above.
        Model ditchMarkerWhiteModel = builder.createCylinder(
            DITCH_MARKER_DIAMETER_M, DITCH_MARKER_HEIGHT_M, DITCH_MARKER_DIAMETER_M, 8,
            new Material(ColorAttribute.createDiffuse(Color.WHITE)), attrs
        );
        ownedModels.add(ditchMarkerWhiteModel);
        Model ditchMarkerBlueModel = builder.createCylinder(
            DITCH_MARKER_DIAMETER_M, DITCH_MARKER_HEIGHT_M, DITCH_MARKER_DIAMETER_M, 8,
            new Material(ColorAttribute.createDiffuse(DITCH_MARKER_BLUE)), attrs
        );
        ownedModels.add(ditchMarkerBlueModel);
        for (int i = 0; i < DITCH_MARKER_COUNT; i++) {
            float markerX = (AussieBowlsPhysics.RINK_WIDTH_M / (DITCH_MARKER_COUNT + 1)) * (i + 1);
            Model markerModel = (i % 2 == 0) ? ditchMarkerWhiteModel : ditchMarkerBlueModel;
            ModelInstance marker = new ModelInstance(markerModel);
            marker.transform.setToTranslation(markerX, DITCH_MARKER_HEIGHT_M / 2f, -AussieRulesEngine.GREEN_LENGTH);
            staticInstances.add(marker);
        }

        buildMat(builder, attrs);

        // Unit-radius spheres, scaled per-instance to the real (visual) radius.
        jackModel = sphere(builder, Color.WHITE, attrs);
        bowlModelPlayer0 = sphere(builder, player0Color, attrs);
        bowlModelPlayer1 = sphere(builder, player1Color, attrs);

        jackInstance = new ModelInstance(jackModel);
    }

    /**
     * A large sphere surrounding the whole scene, textured with {@link #cloudsTexture} and lit
     * fullbright (black diffuse + emissive texture, rather than the flat emissive colour used
     * before) so it reads as sky regardless of the directional light's angle, with culling disabled
     * so its inward-facing surface is visible from inside it. {@code createSphere}'s standard UV
     * mapping runs the texture pole-to-pole/around once by default; {@link TextureAttribute#scaleU}/
     * {@code scaleV} (with the texture's wrap mode set to {@code Repeat}) tile it several times
     * instead — see {@link #SKY_CLOUD_U_REPEAT}.
     */
    private void buildSky(ModelBuilder builder, long attrs) {
        TextureAttribute cloudsAttribute = TextureAttribute.createEmissive(cloudsTexture);
        cloudsAttribute.scaleU = SKY_CLOUD_U_REPEAT;
        cloudsAttribute.scaleV = SKY_CLOUD_V_REPEAT;
        Material material = new Material(
            ColorAttribute.createDiffuse(Color.BLACK),
            cloudsAttribute,
            IntAttribute.createCullFace(GL20.GL_NONE)
        );
        Model skyModel = builder.createSphere(
            SKY_RADIUS_M * 2f, SKY_RADIUS_M * 2f, SKY_RADIUS_M * 2f, 24, 24, material,
            attrs | VertexAttributes.Usage.TextureCoordinates
        );
        ownedModels.add(skyModel);
        ModelInstance sky = new ModelInstance(skyModel);
        sky.transform.setToTranslation(
            AussieBowlsPhysics.RINK_WIDTH_M / 2f, 0f, -AussieRulesEngine.GREEN_LENGTH / 2f
        );
        staticInstances.add(sky);
    }

    private void buildMat(ModelBuilder builder, long attrs) {
        Model matModel = box(builder, MAT_WIDTH_M, MAT_THICKNESS_M, MAT_LENGTH_M, new Color(0.90f, 0.89f, 0.86f, 1f), attrs);
        ModelInstance mat = new ModelInstance(matModel);
        mat.transform.setToTranslation(deliveryOrigin.x, MAT_THICKNESS_M / 2f, -deliveryOrigin.y);
        staticInstances.add(mat);
        matInstance = mat;

        // Two scattered diamond-stud clusters, front and rear of the branding panel.
        Model diamondModel = box(builder, DIAMOND_SIZE_M, DIAMOND_THICKNESS_M, DIAMOND_SIZE_M, new Color(0.10f, 0.35f, 0.14f, 1f), attrs);
        float diamondY = MAT_THICKNESS_M + DIAMOND_THICKNESS_M / 2f;
        addDiamondCluster(diamondModel, deliveryOrigin.y + DIAMOND_CLUSTER_OFFSET_M, diamondY);
        addDiamondCluster(diamondModel, deliveryOrigin.y - DIAMOND_CLUSTER_OFFSET_M, diamondY);

        // TODO: Pittwater RSL logo goes here, textured onto this branding panel. Needs a real logo
        // image (e.g. assets/pittwater-rsl-logo.png) — see chat for why this is a plain placeholder
        // patch rather than an attempt to redraw the actual logo.
        float logoThickness = DIAMOND_THICKNESS_M;
        float logoY = MAT_THICKNESS_M + logoThickness / 2f;
        Model logoPlaceholderModel = box(builder, MAT_WIDTH_M * 0.8f, logoThickness, MAT_LENGTH_M * 0.22f, new Color(0.8f, 0.79f, 0.76f, 1f), attrs);
        ModelInstance logoPlaceholder = new ModelInstance(logoPlaceholderModel);
        logoPlaceholder.transform.setToTranslation(deliveryOrigin.x, logoY, -deliveryOrigin.y);
        staticInstances.add(logoPlaceholder);
    }

    /** Places [DIAMOND_CLUSTER_OFFSETS] as rotated-square "diamond" studs around (deliveryOrigin.x, clusterRinkY). */
    private void addDiamondCluster(Model diamondModel, float clusterRinkY, float y) {
        for (float[] offset : DIAMOND_CLUSTER_OFFSETS) {
            ModelInstance diamond = new ModelInstance(diamondModel);
            diamond.transform.setToTranslation(deliveryOrigin.x + offset[0], y, -(clusterRinkY + offset[1]));
            diamond.transform.rotate(Vector3.Y, 45f);
            staticInstances.add(diamond);
        }
    }

    private Model box(ModelBuilder builder, float width, float height, float depth, Color color, long attrs) {
        Model model = builder.createBox(width, height, depth, new Material(ColorAttribute.createDiffuse(color)), attrs);
        ownedModels.add(model);
        return model;
    }

    /** A box textured (not flat-colored) with [texture], tiled [uRepeat]x[vRepeat] times across its faces. */
    private Model texturedBox(
        ModelBuilder builder, float width, float height, float depth,
        Texture texture, float uRepeat, float vRepeat, long attrs
    ) {
        Material material = new Material(TextureAttribute.createDiffuse(texture));
        builder.begin();
        MeshPartBuilder part = builder.part(
            "box", GL20.GL_TRIANGLES, attrs | VertexAttributes.Usage.TextureCoordinates, material
        );
        part.setUVRange(0f, 0f, uRepeat, vRepeat);
        part.box(width, height, depth);
        Model model = builder.end();
        ownedModels.add(model);
        return model;
    }

    private Model sphere(ModelBuilder builder, Color color, long attrs) {
        Model model = builder.createSphere(2f, 2f, 2f, 16, 16, new Material(ColorAttribute.createDiffuse(color)), attrs);
        ownedModels.add(model);
        return model;
    }

    @Override
    public void render() {
        float delta = Gdx.graphics.getDeltaTime();
        updatePhysics(delta);
        updateEndLifecycle(delta);
        updateAiTurn(delta);
        updateInstanceTransforms();
        updateCamera(delta);

        ScreenUtils.clear(0.5f, 0.7f, 0.9f, 1f, true);

        drawShadows();

        modelBatch.begin(camera);
        modelBatch.render(staticInstances, environment);
        modelBatch.render(jackInstance, environment);
        for (int i = 0; i < bowls.size; i++) {
            if (bowls.get(i).isAlive()) {
                modelBatch.render(bowlInstances.get(i), environment);
            }
        }
        modelBatch.end();

        if (aiming) {
            aimAnimTime += delta;
            float targetDragDistance = new Vector2(aimTarget).sub(deliveryOrigin).len();
            float growSmoothing = 1f - (float) Math.exp(-8f * delta);
            aimVisualDragDistance += (targetDragDistance - aimVisualDragDistance) * growSmoothing;
            drawAimArrow();
        } else {
            aimAnimTime = 0f;
            aimVisualDragDistance = 0f;
        }

        drawScoreboardPanels();

        batch.begin();
        drawHud();
        drawScoreboardText();
        batch.end();
    }

    private void updateInstanceTransforms() {
        float jackRadius = jack.getRadius() * VISUAL_RADIUS_SCALE;
        jackInstance.transform.setToTranslationAndScaling(
            jack.getPosition().x, jackRadius, -jack.getPosition().y,
            jackRadius, jackRadius, jackRadius
        );

        for (int i = 0; i < bowls.size; i++) {
            Bowl bowl = bowls.get(i);
            float radius = bowl.getRadius() * VISUAL_RADIUS_SCALE;
            bowlInstances.get(i).transform.setToTranslationAndScaling(
                bowl.getPosition().x, radius, -bowl.getPosition().y,
                radius, radius, radius
            );
        }
    }

    /**
     * Renders the mat/jack/live-bowl shadow casters into {@link #shadowLight}'s depth map. Grass,
     * ditch, boundary lines, and the background scenery are deliberately excluded — the default
     * shadow shader has no depth-bias term, so large flat casters/receivers show acne at grazing
     * angles.
     */
    private void drawShadows() {
        shadowCasters.clear();
        shadowCasters.add(matInstance);
        shadowCasters.add(jackInstance);
        for (int i = 0; i < bowls.size; i++) {
            if (bowls.get(i).isAlive()) {
                shadowCasters.add(bowlInstances.get(i));
            }
        }

        shadowLight.begin(shadowCenter, shadowLight.direction);
        shadowBatch.begin(shadowLight.getCamera());
        shadowBatch.render(shadowCasters);
        shadowBatch.end();
        shadowLight.end();
    }

    /**
     * Eases the camera toward the tracked bowl while it's moving, clamped to never track past
     * halfway up the green, and back to the idle view once nothing's rolling.
     */
    private void updateCamera(float delta) {
        Bowl tracked = trackedBowlIndex >= 0 && trackedBowlIndex < bowls.size ? bowls.get(trackedBowlIndex) : null;
        boolean isTracking = tracked != null && tracked.isAlive() && tracked.getVelocity().len() > AussieBowlsPhysics.STOP_THRESHOLD;

        if (isTracking) {
            float trackedRinkX = tracked.getPosition().x;
            float trackedRinkY = Math.min(tracked.getPosition().y, AussieRulesEngine.GREEN_LENGTH / 2f);
            float worldZ = -trackedRinkY;
            desiredCameraPosition.set(trackedRinkX, FOLLOW_HEIGHT_M, worldZ + FOLLOW_BACK_DISTANCE_M);
            desiredCameraLookAt.set(trackedRinkX, 0.2f, worldZ - FOLLOW_LOOKAHEAD_M);
        } else {
            desiredCameraPosition.set(idleCameraPosition);
            desiredCameraLookAt.set(idleCameraLookAt);
        }

        float smoothing = 1f - (float) Math.exp(-CAMERA_LERP_SPEED * delta);
        cameraPosition.lerp(desiredCameraPosition, smoothing);
        cameraLookAt.lerp(desiredCameraLookAt, smoothing);
        applyCamera();
    }

    /**
     * A live 3D slingshot band from the mat back to the current drag point, drawn over the 3D
     * scene — it follows the actual drag (behind the mat), not the bowl's eventual travel
     * direction, which is the opposite way (see {@link #releaseBowl()}). Its length eases toward
     * the actual drag distance each frame (see {@link #aimVisualDragDistance}) so it visibly grows
     * as the player pulls back further, and both its thickness and colour scale with the resulting
     * release power ({@link #SPEED_PER_METER_OF_DRAG}). A small power-scaled pulse keeps it
     * animated while held, and a round grip handle marks the drag point.
     */
    private void drawAimArrow() {
        float visualLength = aimVisualDragDistance;
        if (visualLength < 0.02f) {
            return;
        }

        Vector2 pull = new Vector2(aimTarget).sub(deliveryOrigin);
        if (pull.isZero(0.0001f)) {
            return;
        }
        Vector2 dir = new Vector2(pull).nor();
        Vector2 perp = new Vector2(-dir.y, dir.x);

        float powerFraction = MathUtils.clamp(
            visualLength * SPEED_PER_METER_OF_DRAG / (MAX_RELEASE_SPEED - MIN_RELEASE_SPEED), 0f, 1f
        );

        float bandWidth = MathUtils.lerp(0.04f, 0.14f, powerFraction);
        float gripRadius = MathUtils.lerp(0.08f, 0.18f, powerFraction);

        // Subtle pulse so the band reads as "alive" while charging, stronger the more power is loaded.
        float pulse = 1f + 0.08f * powerFraction * MathUtils.sin(aimAnimTime * 6f);
        bandWidth *= pulse;
        gripRadius *= pulse;

        Vector2 tip = new Vector2(deliveryOrigin).mulAdd(dir, visualLength);
        // Starts a modest gold at a light pull and brightens toward a shimmering near-white yellow
        // the further back it's pulled, so more power visibly stands out more.
        float glimmer = 0.5f + 0.5f * MathUtils.sin(aimAnimTime * 8f);
        float brighten = powerFraction * MathUtils.lerp(0.6f, 1f, glimmer);
        Color color = new Color(1f, MathUtils.lerp(0.7f, 1f, brighten), MathUtils.lerp(0f, 0.7f, brighten), 1f);

        // ShapeRenderer's filled triangle() has no 3D (x,y,z) overload, only line() does, so the
        // "filled" band/handle are faked with bundles of 3D line strokes.
        shapeRenderer.setProjectionMatrix(camera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(color);

        int bandStrokes = 5;
        for (int i = 0; i < bandStrokes; i++) {
            float t = MathUtils.lerp(-0.5f, 0.5f, (float) i / (bandStrokes - 1));
            Vector2 from = new Vector2(deliveryOrigin).mulAdd(perp, bandWidth * t);
            Vector2 to = new Vector2(tip).mulAdd(perp, bandWidth * t);
            shapeRenderer.line(from.x, 0.05f, -from.y, to.x, 0.05f, -to.y);
        }

        // Grip handle: a radial fan of strokes reads as a small filled disc at the drag point.
        int gripSpokes = 12;
        for (int i = 0; i < gripSpokes; i++) {
            float angle = MathUtils.PI2 * i / gripSpokes;
            float edgeX = tip.x + MathUtils.cos(angle) * gripRadius;
            float edgeY = tip.y + MathUtils.sin(angle) * gripRadius;
            shapeRenderer.line(tip.x, 0.05f, -tip.y, edgeX, 0.05f, -edgeY);
        }

        shapeRenderer.end();
    }

    /** Turn/bowls-remaining status, drawn top-left as a few short lines. */
    private void drawHud() {
        StringBuilder hud = new StringBuilder();
        if (match.isComplete()) {
            hud.append("Match complete");
        } else {
            String turnLabel = "Player " + (end.getCurrentPlayer() + 1) + (end.getCurrentPlayer() == AI_PLAYER ? " (AI)" : "");
            hud.append(end.isComplete() ? "End complete!" : turnLabel + "'s turn");
            hud.append('\n').append("P1 left: ").append(end.bowlsRemaining(0));
            hud.append('\n').append("P2 left: ").append(end.bowlsRemaining(1));
            if (!end.isComplete() && !canDeliver()) {
                hud.append('\n').append("(rolling...)");
            }
        }
        font.draw(batch, hud.toString(), 8f, Gdx.graphics.getHeight() - 8f);
    }

    /** Whether the current player may deliver right now: the match isn't over and [End.canDeliver]. */
    private boolean canDeliver() {
        return !match.isComplete() && end.canDeliver(bowls);
    }

    /**
     * Scores a just-completed, fully-settled end, records it on {@link #match}, and (after a short
     * on-screen pause, {@link #END_TRANSITION_DELAY_S}) resets the green and starts the next end —
     * handed to the previous end's winner, or replayed by the same starter if the jack was knocked
     * dead ({@link EndResult#isVoid()}).
     */
    private void updateEndLifecycle(float delta) {
        if (match.isComplete()) {
            return;
        }

        if (!endResolved) {
            if (end.isComplete() && end.allSettled(bowls)) {
                EndResult result = Scoring.scoreEnd(bowls, jack);
                match.recordEnd(result);
                lastEndMessage = describeResult(result);
                pendingResult = result;
                endResolved = true;
                endTransitionTimer = END_TRANSITION_DELAY_S;
            }
            return;
        }

        endTransitionTimer -= delta;
        if (endTransitionTimer <= 0f) {
            startNextEnd(pendingResult);
        }
    }

    /**
     * Drives {@link #bowlAi}'s turn: once it's {@link #AI_PLAYER}'s turn and the green has
     * settled, waits {@link #AI_THINK_DELAY_S} (purely for pacing) then delivers whatever
     * {@link BowlAi#chooseDelivery} picks, via the same {@link #deliverBowl} path a human
     * delivery uses.
     */
    private void updateAiTurn(float delta) {
        if (match.isComplete() || end.getCurrentPlayer() != AI_PLAYER || !canDeliver()) {
            aiThinkTimer = 0f;
            return;
        }

        aiThinkTimer += delta;
        if (aiThinkTimer < AI_THINK_DELAY_S) {
            return;
        }

        DeliveryPlan plan = bowlAi.chooseDelivery(deliveryOrigin, jack, AI_PLAYER);
        deliverBowl(plan.getDirection(), plan.getSpeed());
        aiThinkTimer = 0f;
    }

    private String describeResult(EndResult result) {
        if (result.isVoid()) {
            return "Jack out of bounds — end replayed";
        }
        Integer winner = result.getWinner();
        if (winner == null) {
            return "No shots this end";
        }
        return "Player " + (winner + 1) + " scores " + result.getShots();
    }

    /** Clears the green and starts the next end, per {@link #updateEndLifecycle}'s handoff rules. */
    private void startNextEnd(EndResult result) {
        int previousStarter = end.getStartingPlayer();

        bowls.clear();
        bowlInstances.clear();
        trackedBowlIndex = -1;

        jack.getPosition().set(2.5f, 24f);
        jack.getVelocity().setZero();
        jack.setAlive(true);
        jack.setInDitch(false);

        Integer winner = result.getWinner();
        int nextStarter = result.isVoid() || winner == null ? previousStarter : winner;
        end = new End(3, nextStarter);
        endResolved = false;
        pendingResult = null;
    }

    /**
     * Two colour-coded filled panels (one per player, matching {@link #player0Color}/
     * {@link #player1Color}) split by a "v" — the coloured-box part of the scoreboard, drawn in
     * screen space via {@link #shapeRenderer} before {@link #drawScoreboardText} overlays the
     * numbers/labels in the same batch pass as {@link #drawHud}.
     */
    private void drawScoreboardPanels() {
        float totalWidth = SCOREBOARD_PANEL_WIDTH * 2 + SCOREBOARD_PANEL_GAP;
        float left = (Gdx.graphics.getWidth() - totalWidth) / 2f;
        float panelY = Gdx.graphics.getHeight() - SCOREBOARD_TOP_MARGIN - SCOREBOARD_PANEL_HEIGHT;

        shapeRenderer.setProjectionMatrix(batch.getProjectionMatrix());
        shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);
        shapeRenderer.setColor(player0Color);
        shapeRenderer.rect(left, panelY, SCOREBOARD_PANEL_WIDTH, SCOREBOARD_PANEL_HEIGHT);
        shapeRenderer.setColor(player1Color);
        shapeRenderer.rect(
            left + SCOREBOARD_PANEL_WIDTH + SCOREBOARD_PANEL_GAP, panelY, SCOREBOARD_PANEL_WIDTH, SCOREBOARD_PANEL_HEIGHT
        );
        shapeRenderer.end();
    }

    /** Score numbers, the "v" divider, ends counter/result, and transient end message. */
    private void drawScoreboardText() {
        float totalWidth = SCOREBOARD_PANEL_WIDTH * 2 + SCOREBOARD_PANEL_GAP;
        float left = (Gdx.graphics.getWidth() - totalWidth) / 2f;
        float panelY = Gdx.graphics.getHeight() - SCOREBOARD_TOP_MARGIN - SCOREBOARD_PANEL_HEIGHT;
        float panelCenterY = panelY + SCOREBOARD_PANEL_HEIGHT / 2f;
        int[] scores = match.getScores();

        drawCentered(scoreFont, String.valueOf(scores[0]), left + SCOREBOARD_PANEL_WIDTH / 2f, panelCenterY);
        drawCentered(
            scoreFont, String.valueOf(scores[1]),
            left + SCOREBOARD_PANEL_WIDTH + SCOREBOARD_PANEL_GAP + SCOREBOARD_PANEL_WIDTH / 2f, panelCenterY
        );
        drawCentered(font, "v", left + SCOREBOARD_PANEL_WIDTH + SCOREBOARD_PANEL_GAP / 2f, panelCenterY);

        String endsLabel;
        if (match.isComplete()) {
            int winner = match.getWinner();
            endsLabel = "Player " + (winner + 1) + " wins " + scores[winner] + "–" + scores[1 - winner];
        } else if (match.getEndsPlayed() >= 7) {
            endsLabel = "Tie-break end " + (match.getEndsPlayed() + 1);
        } else {
            endsLabel = "End " + (match.getEndsPlayed() + 1) + " / 7";
        }
        drawCentered(font, endsLabel, left + totalWidth / 2f, panelY - 8f);

        if (!lastEndMessage.isEmpty() && endResolved && endTransitionTimer > 0f) {
            drawCentered(font, lastEndMessage, left + totalWidth / 2f, panelY - 24f);
        }
    }

    private void drawCentered(BitmapFont f, String text, float centerX, float centerY) {
        layout.setText(f, text);
        f.draw(batch, text, centerX - layout.width / 2f, centerY + layout.height / 2f);
    }

    private void updatePhysics(float delta) {
        jack.getPosition().mulAdd(jack.getVelocity(), delta);

        for (Bowl bowl : bowls) {
            if (!bowl.isAlive()) {
                continue;
            }

            bowlsPhysics.update(bowl, delta);

            rulesEngine.checkBowlToJackCollision(bowl, jack);
            rulesEngine.updateEntityBounds(bowl, jack);
        }
    }

    /** Converts a slingshot-style pull (drag distance/direction behind the mat) into a released bowl. */
    private void releaseBowl() {
        if (!canDeliver()) {
            return; // end/match is finished, or a previous bowl hasn't come to rest yet
        }

        Vector2 pull = new Vector2(aimTarget).sub(deliveryOrigin);
        float dragDistance = pull.len();
        if (dragDistance < 0.1f) {
            return; // ignore accidental clicks/taps
        }

        // Slingshot-style: the bowl travels opposite the pull, e.g. dragging behind-and-right of
        // the mat launches it to the left.
        Vector2 travelDir = new Vector2(pull).scl(-1f).nor();

        float speed = MathUtils.clamp(
            MIN_RELEASE_SPEED + dragDistance * SPEED_PER_METER_OF_DRAG,
            MIN_RELEASE_SPEED,
            MAX_RELEASE_SPEED
        );
        deliverBowl(travelDir, speed);
    }

    /**
     * Turns a chosen travel direction/speed into a new {@link Bowl} for whoever's currently up
     * ({@link End#getCurrentPlayer()}) — the single place either a human ({@link #releaseBowl})
     * or {@link #bowlAi} ({@link #updateAiTurn}) actually commits a delivery.
     */
    private void deliverBowl(Vector2 travelDir, float speed) {
        // Derived from which side of the mat's centre line the travel direction falls on: heading
        // right is a backhand delivery (bias continues curving left), heading left is forehand
        // (bias continues curving right) — no manual hand toggle needed, for the AI either.
        boolean isBackhand = travelDir.x > 0f;
        Vector2 velocity = new Vector2(travelDir).scl(speed);

        Bowl bowl = new Bowl(
            new Vector2(deliveryOrigin),
            velocity,
            0.065f,
            isBackhand,
            speed,
            end.getCurrentPlayer(),
            true, false, false
        );
        bowls.add(bowl);
        bowlInstances.add(new ModelInstance(bowl.getOwner() == 0 ? bowlModelPlayer0 : bowlModelPlayer1));
        trackedBowlIndex = bowls.size - 1;
        end.recordDelivery();
    }

    /** Screen tap/drag to a point on the green (world Y=0), via ray-plane intersection. */
    private void setAimTargetFromScreen(int screenX, int screenY) {
        Ray ray = camera.getPickRay(screenX, screenY);
        if (Intersector.intersectRayPlane(ray, groundPlane, pickResult)) {
            aimTarget.set(pickResult.x, -pickResult.z);
        }
    }

    @Override
    public void resize(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
    }

    @Override
    public void dispose() {
        batch.dispose();
        font.dispose();
        scoreFont.dispose();
        shapeRenderer.dispose();
        modelBatch.dispose();
        shadowBatch.dispose();
        shadowLight.dispose();
        grassTexture.dispose();
        cloudsTexture.dispose();
        for (Model model : ownedModels) {
            model.dispose();
        }
    }

    /**
     * Click-drag-release to aim and set weight, slingshot-style: drag distance behind the mat maps
     * to release speed ({@link #SPEED_PER_METER_OF_DRAG}), and the bowl's heading is the opposite
     * of the drag direction (e.g. dragging right of the mat launches left) — see
     * {@link #releaseBowl}.
     */
    private class DeliveryInputAdapter extends InputAdapter {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (button != Input.Buttons.LEFT) {
                return false;
            }
            if (!canDeliver()) {
                return false; // wait for the green to settle, the end to be reset, or the match is over
            }
            if (end.getCurrentPlayer() == AI_PLAYER) {
                return false; // it's the computer's turn — see updateAiTurn()
            }
            aiming = true;
            setAimTargetFromScreen(screenX, screenY);
            return true;
        }

        @Override
        public boolean touchDragged(int screenX, int screenY, int pointer) {
            if (!aiming) {
                return false;
            }
            setAimTargetFromScreen(screenX, screenY);
            return true;
        }

        @Override
        public boolean touchUp(int screenX, int screenY, int pointer, int button) {
            if (!aiming || button != Input.Buttons.LEFT) {
                return false;
            }
            setAimTargetFromScreen(screenX, screenY);
            releaseBowl();
            aiming = false;
            return true;
        }
    }
}
