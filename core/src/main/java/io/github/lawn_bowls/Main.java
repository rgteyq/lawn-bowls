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
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
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
import io.github.lawn_bowls.game.End;
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
    private static final float DITCH_RECESS_M = 0.15f; // ditch floor sits below the green surface
    private static final float DITCH_THICKNESS_M = 0.10f;
    private static final float BOUNDARY_LINE_WIDTH_M = 0.05f;
    private static final float BOUNDARY_LINE_HEIGHT_M = 0.02f;

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
    // visibility. Collision/physics still use the real Bowl.radius/Jack.radius. The jack gets its
    // own, larger scale — at true-to-scale proportions it's the hardest thing on the green to spot.
    private static final float BOWL_VISUAL_RADIUS_SCALE = 1.6f;
    private static final float JACK_VISUAL_RADIUS_SCALE = 4.0f;

    private static final float MIN_RELEASE_SPEED = 1.0f;
    private static final float MAX_RELEASE_SPEED = 3.5f;
    // Dragging all the way from the mat to the far ditch line charges full power.
    private static final float SPEED_PER_METER_OF_DRAG =
        (MAX_RELEASE_SPEED - MIN_RELEASE_SPEED) / AussieRulesEngine.GREEN_LENGTH;

    // Follow-cam: while the tracked bowl is moving, the camera closes in on it, but never tracks
    // past halfway up the green — it eases to a stop there instead of following all the way to
    // the jack. Once the bowl stops or dies, the camera eases back out to the idle view.
    private static final float FOLLOW_HEIGHT_M = 2.0f;
    private static final float FOLLOW_BACK_DISTANCE_M = 2.5f;
    private static final float FOLLOW_LOOKAHEAD_M = 6.0f;
    private static final float CAMERA_LERP_SPEED = 3.0f;

    private SpriteBatch batch;
    private BitmapFont font;
    private ShapeRenderer shapeRenderer;
    private Texture grassTexture;

    private PerspectiveCamera camera;
    private ModelBatch modelBatch;
    private Environment environment;

    // Reusable geometry, built once in create() and disposed in dispose().
    private final Array<Model> ownedModels = new Array<>();
    private final Array<ModelInstance> staticInstances = new Array<>();
    private Model jackModel;
    private Model bowlModelPlayer0;
    private Model bowlModelPlayer1;

    private ModelInstance jackInstance;
    // Index-aligned with `bowls`: bowlInstances.get(i) renders bowls.get(i).
    private final Array<ModelInstance> bowlInstances = new Array<>();

    private AussieRulesEngine rulesEngine;
    private AussieBowlsPhysics bowlsPhysics;
    private End end;
    private Jack jack;
    private Array<Bowl> bowls;

    private final Vector2 deliveryOrigin = new Vector2(AussieBowlsPhysics.RINK_WIDTH_M / 2f, 1.0f);
    private final Vector2 aimTarget = new Vector2();
    private boolean aiming = false;

    private final Plane groundPlane = new Plane(Vector3.Y, 0f);
    private final Vector3 pickResult = new Vector3();

    // Pulled back further than the mat's own position (rinkY=1) so the whole mat clears the
    // vertical field of view instead of sitting right at its edge and getting clipped.
    private final Vector3 idleCameraPosition = new Vector3(2.5f, 4.0f, 6.0f);
    private final Vector3 idleCameraLookAt = new Vector3(2.5f, 0.2f, -20f);
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
        shapeRenderer = new ShapeRenderer();

        rulesEngine = new AussieRulesEngine();
        bowlsPhysics = new AussieBowlsPhysics();
        end = new End();
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
        camera.far = 60f;
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
        environment.add(new DirectionalLight().set(0.8f, 0.8f, 0.75f, -0.4f, -1f, -0.3f));
    }

    /** Builds the static geometry (grass, ditch, boundary lines, mat) and the reusable entity models. */
    private void buildScene() {
        modelBatch = new ModelBatch();
        ModelBuilder builder = new ModelBuilder();
        long attrs = VertexAttributes.Usage.Position | VertexAttributes.Usage.Normal;

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

        Model ditchModel = box(builder, AussieBowlsPhysics.RINK_WIDTH_M, DITCH_THICKNESS_M, AussieRulesEngine.DITCH_DEPTH, Color.DARK_GRAY, attrs);
        ModelInstance ditch = new ModelInstance(ditchModel);
        ditch.transform.setToTranslation(
            AussieBowlsPhysics.RINK_WIDTH_M / 2f,
            -DITCH_RECESS_M - DITCH_THICKNESS_M / 2f,
            -(AussieRulesEngine.GREEN_LENGTH + AussieRulesEngine.DITCH_DEPTH / 2f)
        );
        staticInstances.add(ditch);

        // Rink boundary markers along both long edges, from the mat end to the back of the ditch.
        float fullLength = AussieRulesEngine.GREEN_LENGTH + AussieRulesEngine.DITCH_DEPTH;
        Model boundaryModel = box(builder, BOUNDARY_LINE_WIDTH_M, BOUNDARY_LINE_HEIGHT_M, fullLength, Color.WHITE, attrs);
        ModelInstance leftBoundary = new ModelInstance(boundaryModel);
        leftBoundary.transform.setToTranslation(BOUNDARY_LINE_WIDTH_M / 2f, BOUNDARY_LINE_HEIGHT_M / 2f, -fullLength / 2f);
        staticInstances.add(leftBoundary);
        ModelInstance rightBoundary = new ModelInstance(boundaryModel);
        rightBoundary.transform.setToTranslation(
            AussieBowlsPhysics.RINK_WIDTH_M - BOUNDARY_LINE_WIDTH_M / 2f, BOUNDARY_LINE_HEIGHT_M / 2f, -fullLength / 2f
        );
        staticInstances.add(rightBoundary);

        buildMat(builder, attrs);

        // Unit-radius spheres, scaled per-instance to the real (visual) radius.
        jackModel = sphere(builder, Color.WHITE, attrs);
        bowlModelPlayer0 = sphere(builder, player0Color, attrs);
        bowlModelPlayer1 = sphere(builder, player1Color, attrs);

        jackInstance = new ModelInstance(jackModel);
    }

    private void buildMat(ModelBuilder builder, long attrs) {
        Model matModel = box(builder, MAT_WIDTH_M, MAT_THICKNESS_M, MAT_LENGTH_M, new Color(0.90f, 0.89f, 0.86f, 1f), attrs);
        ModelInstance mat = new ModelInstance(matModel);
        mat.transform.setToTranslation(deliveryOrigin.x, MAT_THICKNESS_M / 2f, -deliveryOrigin.y);
        staticInstances.add(mat);

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
        updateInstanceTransforms();
        updateCamera(delta);

        ScreenUtils.clear(0.5f, 0.7f, 0.9f, 1f, true);

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
            drawAimLine();
        }

        batch.begin();
        drawHud();
        batch.end();
    }

    private void updateInstanceTransforms() {
        float jackRadius = jack.getRadius() * JACK_VISUAL_RADIUS_SCALE;
        jackInstance.transform.setToTranslationAndScaling(
            jack.getPosition().x, jackRadius, -jack.getPosition().y,
            jackRadius, jackRadius, jackRadius
        );

        for (int i = 0; i < bowls.size; i++) {
            Bowl bowl = bowls.get(i);
            float radius = bowl.getRadius() * BOWL_VISUAL_RADIUS_SCALE;
            bowlInstances.get(i).transform.setToTranslationAndScaling(
                bowl.getPosition().x, radius, -bowl.getPosition().y,
                radius, radius, radius
            );
        }
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

    /** A live 3D line from the mat to the current aim point, drawn over the 3D scene. */
    private void drawAimLine() {
        shapeRenderer.setProjectionMatrix(camera.combined);
        shapeRenderer.begin(ShapeRenderer.ShapeType.Line);
        shapeRenderer.setColor(Color.YELLOW);
        shapeRenderer.line(
            deliveryOrigin.x, 0.05f, -deliveryOrigin.y,
            aimTarget.x, 0.05f, -aimTarget.y
        );
        shapeRenderer.end();
    }

    /** Turn/bowls-remaining status, drawn top-left as a few short lines. */
    private void drawHud() {
        StringBuilder hud = new StringBuilder();
        hud.append(end.isComplete() ? "End complete!" : "Player " + (end.getCurrentPlayer() + 1) + "'s turn");
        hud.append('\n').append("P1 left: ").append(end.bowlsRemaining(0));
        hud.append('\n').append("P2 left: ").append(end.bowlsRemaining(1));
        if (!end.isComplete() && !end.canDeliver(bowls)) {
            hud.append('\n').append("(rolling...)");
        }
        font.draw(batch, hud.toString(), 8f, Gdx.graphics.getHeight() - 8f);
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

    /** Converts an aim point (drag distance/direction from the mat) into a released bowl. */
    private void releaseBowl() {
        if (!end.canDeliver(bowls)) {
            return; // end is finished, or a previous bowl hasn't come to rest yet
        }

        Vector2 offset = new Vector2(aimTarget).sub(deliveryOrigin);
        float dragDistance = offset.len();
        if (dragDistance < 0.1f) {
            return; // ignore accidental clicks/taps
        }

        // Derived from which side of the mat's centre line the aim point falls on: dragging right
        // is a backhand delivery (bias continues curving left), dragging left is forehand (bias
        // continues curving right) — no manual hand toggle needed.
        boolean isBackhand = offset.x > 0f;

        float speed = MathUtils.clamp(
            MIN_RELEASE_SPEED + dragDistance * SPEED_PER_METER_OF_DRAG,
            MIN_RELEASE_SPEED,
            MAX_RELEASE_SPEED
        );
        Vector2 velocity = offset.nor().scl(speed);

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
        shapeRenderer.dispose();
        modelBatch.dispose();
        grassTexture.dispose();
        for (Model model : ownedModels) {
            model.dispose();
        }
    }

    /**
     * Click-drag-release to aim and set weight: drag distance from the mat maps to release speed
     * ({@link #SPEED_PER_METER_OF_DRAG}), drag direction becomes the bowl's heading, and which side
     * of the mat the aim point falls on determines forehand vs backhand (see {@link #releaseBowl}).
     */
    private class DeliveryInputAdapter extends InputAdapter {
        @Override
        public boolean touchDown(int screenX, int screenY, int pointer, int button) {
            if (button != Input.Buttons.LEFT) {
                return false;
            }
            if (!end.canDeliver(bowls)) {
                return false; // wait for the green to settle, or the end to be reset
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
