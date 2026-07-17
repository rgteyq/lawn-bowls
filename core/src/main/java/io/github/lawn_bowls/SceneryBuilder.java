package io.github.lawn_bowls;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.VertexAttributes;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.attributes.TextureAttribute;
import com.badlogic.gdx.graphics.g3d.utils.MeshPartBuilder;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.utils.Array;
import io.github.lawn_bowls.physics.AussieBowlsPhysics;
import io.github.lawn_bowls.rules.AussieRulesEngine;

/**
 * Builds everything outside the playable rink itself — ground, hedge, adjacent rinks, a clubhouse,
 * a spectator area, and trees — so {@link Main#buildScene()} can stay focused on gameplay geometry.
 * Flat-colored/procedural only, matching the existing mat/boundary style: no new photographic
 * textures, except reusing the rink's own grass texture on the adjacent rink strips.
 */
final class SceneryBuilder {
    private static final float GROUND_SIZE_M = 200f;
    private static final float GROUND_THICKNESS_M = 0.02f;
    // Clears the grass box's underside (top -0.01f, bottom -0.03f at GREEN_THICKNESS_M=0.02f) with margin.
    private static final float GROUND_Y = -0.06f;

    private static final float HEDGE_HEIGHT_M = 0.6f;
    private static final float HEDGE_WIDTH_M = 0.4f;
    // Set back from the rink centreline far enough to clear the adjacent rink strips below
    // (X [-6,-1] and [6,11], i.e. up to 8.5m from this rink's centre at X=2.5).
    private static final float HEDGE_X_OFFSET_M = 10f;
    private static final float HEDGE_NEAR_Z = 2f; // slightly camera-side of the mat
    private static final float HEDGE_FAR_Z = -(AussieRulesEngine.DITCH_BACK_WALL + 3f); // past the ditch, with margin

    private static final float RINK_CENTER_X = AussieBowlsPhysics.RINK_WIDTH_M / 2f;
    private static final float RINK_LENGTH_M = AussieRulesEngine.DITCH_BACK_WALL; // green + ditch
    private static final float ADJACENT_RINK_GAP_M = 1f; // verge between rinks
    private static final float ADJACENT_RINK_OFFSET_M = ADJACENT_RINK_GAP_M + AussieBowlsPhysics.RINK_WIDTH_M;

    private static final float CLUBHOUSE_WIDTH_M = 8f;
    private static final float CLUBHOUSE_HEIGHT_M = 4f;
    private static final float CLUBHOUSE_DEPTH_M = 6f;
    private static final float CLUBHOUSE_ROOF_HEIGHT_M = 0.6f;
    private static final float CLUBHOUSE_Z = -(AussieRulesEngine.DITCH_BACK_WALL + 10f);

    private static final float BENCH_WIDTH_M = 1.6f;
    private static final float BENCH_HEIGHT_M = 0.4f;
    private static final float BENCH_DEPTH_M = 0.4f;
    private static final float[] BENCH_X_OFFSETS = {-3f, 0f, 3f};
    private static final float BENCH_Z = CLUBHOUSE_Z + CLUBHOUSE_DEPTH_M / 2f + 2f;

    private static final float TREE_TRUNK_WIDTH_M = 0.2f;
    private static final float TREE_TRUNK_HEIGHT_M = 2f;
    private static final float TREE_FOLIAGE_RADIUS_M = 1.5f;
    // Scattered just outside each hedge line, plus a couple flanking the clubhouse.
    private static final float[][] TREE_OFFSETS = {
        {-(HEDGE_X_OFFSET_M + 1.5f), -4f}, {-(HEDGE_X_OFFSET_M + 1.5f), -14f}, {-(HEDGE_X_OFFSET_M + 1.5f), -26f},
        {HEDGE_X_OFFSET_M + 1.5f, -4f}, {HEDGE_X_OFFSET_M + 1.5f, -14f}, {HEDGE_X_OFFSET_M + 1.5f, -26f},
        {-(CLUBHOUSE_WIDTH_M / 2f + 2f), CLUBHOUSE_Z}, {CLUBHOUSE_WIDTH_M / 2f + 2f, CLUBHOUSE_Z}
    };

    private SceneryBuilder() {
    }

    /** Adds a large ground plane (so the rink doesn't float in the sky) and a hedge perimeter. */
    static void buildGroundAndHedge(
        ModelBuilder builder, long attrs, Array<ModelInstance> staticInstances, Array<Model> ownedModels
    ) {
        Model groundModel = box(builder, GROUND_SIZE_M, GROUND_THICKNESS_M, GROUND_SIZE_M, new Color(0.16f, 0.32f, 0.14f, 1f), attrs, ownedModels);
        ModelInstance ground = new ModelInstance(groundModel);
        ground.transform.setToTranslation(RINK_CENTER_X, GROUND_Y, (HEDGE_NEAR_Z + HEDGE_FAR_Z) / 2f);
        staticInstances.add(ground);

        float hedgeLength = HEDGE_NEAR_Z - HEDGE_FAR_Z;
        float hedgeCenterZ = (HEDGE_NEAR_Z + HEDGE_FAR_Z) / 2f;
        Model hedgeModel = box(builder, HEDGE_WIDTH_M, HEDGE_HEIGHT_M, hedgeLength, new Color(0.10f, 0.28f, 0.10f, 1f), attrs, ownedModels);

        ModelInstance leftHedge = new ModelInstance(hedgeModel);
        leftHedge.transform.setToTranslation(RINK_CENTER_X - HEDGE_X_OFFSET_M, HEDGE_HEIGHT_M / 2f, hedgeCenterZ);
        staticInstances.add(leftHedge);

        ModelInstance rightHedge = new ModelInstance(hedgeModel);
        rightHedge.transform.setToTranslation(RINK_CENTER_X + HEDGE_X_OFFSET_M, HEDGE_HEIGHT_M / 2f, hedgeCenterZ);
        staticInstances.add(rightHedge);
    }

    /**
     * Adds a rink either side of the playable one (reusing its grass texture), a simple clubhouse
     * beyond the ditch, a small spectator bench area in front of it, and a handful of trees around
     * the perimeter — enough to read as "a bowls club" rather than a green floating alone.
     */
    static void buildSurroundings(
        ModelBuilder builder, long attrs, Texture grassTexture, float grassURepeat, float grassVRepeat,
        Array<ModelInstance> staticInstances, Array<Model> ownedModels
    ) {
        Model adjacentRinkModel = texturedBox(
            builder, AussieBowlsPhysics.RINK_WIDTH_M, 0.02f, RINK_LENGTH_M,
            grassTexture, grassURepeat, grassVRepeat, attrs, ownedModels
        );
        float rinkZ = -RINK_LENGTH_M / 2f;
        ModelInstance leftRink = new ModelInstance(adjacentRinkModel);
        leftRink.transform.setToTranslation(RINK_CENTER_X - ADJACENT_RINK_OFFSET_M, -0.01f, rinkZ);
        staticInstances.add(leftRink);
        ModelInstance rightRink = new ModelInstance(adjacentRinkModel);
        rightRink.transform.setToTranslation(RINK_CENTER_X + ADJACENT_RINK_OFFSET_M, -0.01f, rinkZ);
        staticInstances.add(rightRink);

        Model wallsModel = box(builder, CLUBHOUSE_WIDTH_M, CLUBHOUSE_HEIGHT_M, CLUBHOUSE_DEPTH_M, new Color(0.82f, 0.78f, 0.70f, 1f), attrs, ownedModels);
        ModelInstance walls = new ModelInstance(wallsModel);
        walls.transform.setToTranslation(RINK_CENTER_X, CLUBHOUSE_HEIGHT_M / 2f, CLUBHOUSE_Z);
        staticInstances.add(walls);

        Model roofModel = box(
            builder, CLUBHOUSE_WIDTH_M * 1.15f, CLUBHOUSE_ROOF_HEIGHT_M, CLUBHOUSE_DEPTH_M * 1.15f,
            new Color(0.45f, 0.20f, 0.16f, 1f), attrs, ownedModels
        );
        ModelInstance roof = new ModelInstance(roofModel);
        roof.transform.setToTranslation(RINK_CENTER_X, CLUBHOUSE_HEIGHT_M + CLUBHOUSE_ROOF_HEIGHT_M / 2f, CLUBHOUSE_Z);
        staticInstances.add(roof);

        Model benchModel = box(builder, BENCH_WIDTH_M, BENCH_HEIGHT_M, BENCH_DEPTH_M, new Color(0.35f, 0.24f, 0.15f, 1f), attrs, ownedModels);
        for (float xOffset : BENCH_X_OFFSETS) {
            ModelInstance bench = new ModelInstance(benchModel);
            bench.transform.setToTranslation(RINK_CENTER_X + xOffset, BENCH_HEIGHT_M / 2f, BENCH_Z);
            staticInstances.add(bench);
        }

        Model trunkModel = box(builder, TREE_TRUNK_WIDTH_M, TREE_TRUNK_HEIGHT_M, TREE_TRUNK_WIDTH_M, new Color(0.32f, 0.22f, 0.14f, 1f), attrs, ownedModels);
        Model foliageModel = sphere(builder, new Color(0.14f, 0.36f, 0.12f, 1f), attrs, ownedModels);
        for (float[] offset : TREE_OFFSETS) {
            float treeX = RINK_CENTER_X + offset[0];
            float treeZ = offset[1];

            ModelInstance trunk = new ModelInstance(trunkModel);
            trunk.transform.setToTranslation(treeX, TREE_TRUNK_HEIGHT_M / 2f, treeZ);
            staticInstances.add(trunk);

            ModelInstance foliage = new ModelInstance(foliageModel);
            foliage.transform.setToTranslationAndScaling(
                treeX, TREE_TRUNK_HEIGHT_M + TREE_FOLIAGE_RADIUS_M * 0.6f, treeZ,
                TREE_FOLIAGE_RADIUS_M, TREE_FOLIAGE_RADIUS_M, TREE_FOLIAGE_RADIUS_M
            );
            staticInstances.add(foliage);
        }
    }

    private static Model box(
        ModelBuilder builder, float width, float height, float depth, Color color, long attrs, Array<Model> ownedModels
    ) {
        Model model = builder.createBox(width, height, depth, new Material(ColorAttribute.createDiffuse(color)), attrs);
        ownedModels.add(model);
        return model;
    }

    /** Unit-radius sphere, scaled per-instance — mirrors {@code Main.sphere()}. */
    private static Model sphere(ModelBuilder builder, Color color, long attrs, Array<Model> ownedModels) {
        Model model = builder.createSphere(2f, 2f, 2f, 16, 16, new Material(ColorAttribute.createDiffuse(color)), attrs);
        ownedModels.add(model);
        return model;
    }

    /** A box textured (not flat-colored) with [texture], tiled [uRepeat]x[vRepeat] times — mirrors {@code Main.texturedBox()}. */
    private static Model texturedBox(
        ModelBuilder builder, float width, float height, float depth,
        Texture texture, float uRepeat, float vRepeat, long attrs, Array<Model> ownedModels
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
}
