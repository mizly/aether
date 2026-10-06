package dev.aether.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Quadrant;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.object.chest.ChestModel;
import net.minecraft.client.model.object.skull.SkullModel;
import net.minecraft.client.renderer.FaceInfo;
import net.minecraft.client.renderer.blockentity.BedRenderer;
import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.CuboidModelElement;
import net.minecraft.client.resources.model.cuboid.CuboidRotation;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// lays icons out the way vanilla's gui item atlas renders them: the model's gui transform in a y-down slot, the
// ITEMS_3D / ITEMS_FLAT light rig, back faces culled and the rest sorted far to near so painting in order is correct
final class IsoBlockPainter {

    // one quad: (a b c d e f) maps normalised texture uv into icon space (0..1, y down); colour is shade times tint
    record Face(String texture, float a, float b, float c, float d, float e, float f,
                float u0, float v0, float u1, float v1, int color) {}

    // gui transforms of block.json, template_skull, template_chest and template_bed; translations are in blocks
    static final ItemTransform BLOCK_GUI = new ItemTransform(new Vector3f(30f, 225f, 0f), new Vector3f(),
            new Vector3f(0.625f, 0.625f, 0.625f));
    private static final ItemTransform SKULL_GUI = new ItemTransform(new Vector3f(30f, 45f, 0f),
            new Vector3f(0f, 3f / 16f, 0f), new Vector3f(1f, 1f, 1f));
    private static final ItemTransform CHEST_GUI = new ItemTransform(new Vector3f(30f, 45f, 0f), new Vector3f(),
            new Vector3f(0.625f, 0.625f, 0.625f));
    private static final ItemTransform BED_GUI = new ItemTransform(new Vector3f(30f, 160f, 0f),
            new Vector3f(2f / 16f, 3f / 16f, 0f), new Vector3f(0.5325f, 0.5325f, 0.5325f));

    // the ITEMS_3D and ITEMS_FLAT rigs from Lighting, in the same gui space as the transformed normals
    private static final Vector3f[] SIDE_LIGHT = lights(new Matrix4f().scaling(1f, -1f, 1f)
            .rotateYXZ(1.0821041f, 3.2375858f, 0f).rotateYXZ(-0.3926991f, 2.3561945f, 0f));
    private static final Vector3f[] FRONT_LIGHT = lights(new Matrix4f().rotationY(-0.3926991f).rotateX(2.3561945f));

    private static final CuboidFace.UVs FULL_FACE = new CuboidFace.UVs(0f, 0f, 16f, 16f);

    private static final Map<McIcon, List<Face>> cache = new ConcurrentHashMap<>();

    private IsoBlockPainter() {}

    // resolve Item references first; they have no faces of their own
    static List<Face> faces(McIcon icon) {
        return cache.computeIfAbsent(icon, IsoBlockPainter::build);
    }

    static void invalidate() {
        cache.clear();
    }

    // vanilla's light.glsl: two directional lights at 0.6 over a 0.4 ambient floor
    static float shade(Vector3fc normal, boolean frontLight) {
        Vector3f[] lights = frontLight ? FRONT_LIGHT : SIDE_LIGHT;
        Vector3f n = new Vector3f(normal).normalize();
        return Math.min(1f, (Math.max(0f, lights[0].dot(n)) + Math.max(0f, lights[1].dot(n))) * 0.6f + 0.4f);
    }

    private static List<Face> build(McIcon icon) {
        Projection projection = new Projection();
        switch (icon) {
            case McIcon.Sprite sprite -> projection.flat(sprite);
            case McIcon.Layered layered -> layered.layers().forEach(projection::flat);
            case McIcon.Block block -> cube(projection, block);
            case McIcon.Model model -> model(projection, model);
            case McIcon.Head head -> head(projection, head.skin());
            case McIcon.Special special -> special(projection, special);
            case McIcon.Item _ -> {}
        }
        return projection.faces();
    }

    // only the three faces a cube shows; the inset moves the side planes in like the cactus model's side elements
    private static void cube(Projection projection, McIcon.Block block) {
        PoseStack pose = guiPose(BLOCK_GUI);
        float inset = block.sideInset() * 16f;
        projection.element(pose, Direction.UP, new Vector3f(0f, 0f, 0f), new Vector3f(16f, 16f, 16f), FULL_FACE,
                Quadrant.R0, null, block.top(), McIcon.UNTINTED, true, false);
        projection.element(pose, Direction.EAST, new Vector3f(inset, 0f, 0f), new Vector3f(16f - inset, 16f, 16f),
                FULL_FACE, Quadrant.R0, null, block.left(), McIcon.UNTINTED, true, false);
        projection.element(pose, Direction.NORTH, new Vector3f(0f, 0f, inset), new Vector3f(16f, 16f, 16f - inset),
                FULL_FACE, Quadrant.R0, null, block.right(), McIcon.UNTINTED, true, false);
    }

    private static void model(Projection projection, McIcon.Model icon) {
        McModels.Resolved model = McModels.resolve(icon.model());
        if (model == null) return;
        PoseStack pose = guiPose(model.gui());
        for (CuboidModelElement element : model.elements()) {
            for (Map.Entry<Direction, CuboidFace> entry : element.faces().entrySet()) {
                CuboidFace face = entry.getValue();
                String texture = faceTexture(model, face.texture());
                if (texture == null) continue;
                CuboidFace.UVs uvs = face.uvs() != null ? face.uvs()
                        : defaultUvs(element.from(), element.to(), entry.getKey());
                int tint = face.tintIndex() >= 0 && face.tintIndex() < icon.tints().size()
                        ? icon.tints().get(face.tintIndex()) : McIcon.UNTINTED;
                projection.element(pose, entry.getKey(), element.from(), element.to(), uvs, face.rotation(),
                        element.rotation(), texture, tint, element.shade(), model.frontLight());
            }
        }
    }

    private static void head(Projection projection, String skin) {
        PoseStack pose = guiPose(SKULL_GUI);
        // player_head.json's own transformation: moved to the block centre and flipped upright
        pose.translate(0.5f, 0f, 0.5f);
        pose.mulPose(new Quaternionf(1f, 0f, 0f, 0f));
        projection.entity(SkullModel.createHumanoidHeadLayer().bakeRoot(), pose, skin);
    }

    private static void special(Projection projection, McIcon.Special special) {
        switch (special.kind()) {
            case CHEST -> projection.entity(ChestModel.createSingleBodyLayer().bakeRoot(), guiPose(CHEST_GUI),
                    special.texture());
            case BED -> {
                PoseStack pose = guiPose(BED_GUI);
                bedPiece(projection, pose, BedRenderer.createHeadLayer(), 1f, special.texture());
                bedPiece(projection, pose, BedRenderer.createFootLayer(), 0f, special.texture());
            }
        }
    }

    // the bed item json composes both pieces, each laid flat and placed one block apart
    private static void bedPiece(Projection projection, PoseStack pose, LayerDefinition piece, float z, String texture) {
        pose.pushPose();
        pose.translate(1f, 0.5625f, z);
        pose.mulPose(new Quaternionf(0f, -0.70710677f, 0.70710677f, 0f));
        projection.entity(piece.bakeRoot(), pose, texture);
        pose.popPose();
    }

    // the gui item atlas slot: centred, y flipped to screen space, then the model's own gui transform
    static PoseStack guiPose(ItemTransform gui) {
        PoseStack pose = new PoseStack();
        pose.translate(0.5f, 0.5f, 0f);
        pose.scale(1f, -1f, 1f);
        gui.apply(false, pose.last());
        return pose;
    }

    private static String faceTexture(McModels.Resolved model, String reference) {
        Identifier sprite = reference.startsWith("#")
                ? model.sprite(reference.substring(1))
                : Identifier.tryParse(reference);
        return sprite == null ? null : McIcons.texture(sprite);
    }

    // the uvs FaceBakery gives faces that leave them out
    static CuboidFace.UVs defaultUvs(Vector3fc from, Vector3fc to, Direction direction) {
        return switch (direction) {
            case DOWN -> new CuboidFace.UVs(from.x(), 16f - to.z(), to.x(), 16f - from.z());
            case UP -> new CuboidFace.UVs(from.x(), from.z(), to.x(), to.z());
            case NORTH -> new CuboidFace.UVs(16f - to.x(), 16f - to.y(), 16f - from.x(), 16f - from.y());
            case SOUTH -> new CuboidFace.UVs(from.x(), 16f - to.y(), to.x(), 16f - from.y());
            case WEST -> new CuboidFace.UVs(from.z(), 16f - to.y(), to.z(), 16f - from.y());
            case EAST -> new CuboidFace.UVs(16f - to.z(), 16f - to.y(), 16f - from.z(), 16f - from.y());
        };
    }

    private static Vector3f[] lights(Matrix4f rig) {
        return new Vector3f[]{
                rig.transformDirection(new Vector3f(0.2f, 1f, -0.7f).normalize()),
                rig.transformDirection(new Vector3f(-0.2f, 1f, 0.7f).normalize())};
    }

    private static int shaded(int tint, float brightness) {
        int r = Math.round(((tint >> 16) & 0xFF) * brightness);
        int g = Math.round(((tint >> 8) & 0xFF) * brightness);
        int b = Math.round((tint & 0xFF) * brightness);
        return (tint & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private static final class Projection {

        // x, y and z of the projected corners; null for flat layers, which keep their order
        private record Entry(Face face, float[] x, float[] y, float[] z, float depth) {}

        private final List<Entry> entries = new ArrayList<>();

        void flat(McIcon.Sprite sprite) {
            entries.add(new Entry(new Face(sprite.texture(), 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 1f, sprite.tint()),
                    null, null, null, entries.size()));
        }

        void element(PoseStack pose, Direction direction, Vector3fc from, Vector3fc to, CuboidFace.UVs uvs,
                     Quadrant uvRotation, CuboidRotation rotation, String texture, int tint, boolean shade,
                     boolean frontLight) {
            FaceInfo info = FaceInfo.fromFacing(direction);
            Vector3f[] corners = new Vector3f[4];
            float[] u = new float[4];
            float[] v = new float[4];
            for (int i = 0; i < 4; i++) {
                Vector3f corner = info.getVertexInfo(i).select(from, to).div(16f);
                if (rotation != null) {
                    rotation.transform().transformPosition(corner.sub(rotation.origin())).add(rotation.origin());
                }
                corners[i] = corner;
                u[i] = CuboidFace.getU(uvs, uvRotation, i);
                v[i] = CuboidFace.getV(uvs, uvRotation, i);
            }
            Vector3f normal = new Vector3f(direction.getUnitVec3f());
            if (rotation != null) rotation.transform().transformDirection(normal);
            add(pose.last(), texture, corners, u, v, normal, tint, shade, frontLight);
        }

        void entity(ModelPart root, PoseStack pose, String texture) {
            root.visit(pose, (part, path, index, cube) -> {
                for (ModelPart.Polygon polygon : cube.polygons) {
                    ModelPart.Vertex[] vertices = polygon.vertices();
                    Vector3f[] corners = new Vector3f[4];
                    float[] u = new float[4];
                    float[] v = new float[4];
                    for (int i = 0; i < 4; i++) {
                        corners[i] = new Vector3f(vertices[i].worldX(), vertices[i].worldY(), vertices[i].worldZ());
                        u[i] = vertices[i].u();
                        v[i] = vertices[i].v();
                    }
                    add(part, texture, corners, u, v, polygon.normal(), McIcon.UNTINTED, true, false);
                }
            });
        }

        // fits the affine that takes uv corners 0, 1 and 3 onto their projected positions
        private void add(PoseStack.Pose pose, String texture, Vector3f[] corners, float[] u, float[] v,
                         Vector3fc normal, int tint, boolean shade, boolean frontLight) {
            Vector3f facing = pose.transformNormal(normal, new Vector3f());
            if (facing.z() <= 1e-4f) return;
            float[] x = new float[4];
            float[] y = new float[4];
            float[] z = new float[4];
            for (int i = 0; i < 4; i++) {
                Vector3f corner = pose.pose().transformPosition(corners[i]);
                x[i] = corner.x();
                y[i] = corner.y();
                z[i] = corner.z();
            }
            float du1 = u[1] - u[0];
            float dv1 = v[1] - v[0];
            float du3 = u[3] - u[0];
            float dv3 = v[3] - v[0];
            float det = du1 * dv3 - du3 * dv1;
            if (Math.abs(det) < 1e-9f) return;
            float a = ((x[1] - x[0]) * dv3 - (x[3] - x[0]) * dv1) / det;
            float c = ((x[3] - x[0]) * du1 - (x[1] - x[0]) * du3) / det;
            float b = ((y[1] - y[0]) * dv3 - (y[3] - y[0]) * dv1) / det;
            float d = ((y[3] - y[0]) * du1 - (y[1] - y[0]) * du3) / det;
            float e = x[0] - a * u[0] - c * v[0];
            float f = y[0] - b * u[0] - d * v[0];
            float brightness = shade ? shade(facing, frontLight) : 1f;
            Face face = new Face(texture, a, b, c, d, e, f,
                    Math.min(Math.min(u[0], u[1]), Math.min(u[2], u[3])),
                    Math.min(Math.min(v[0], v[1]), Math.min(v[2], v[3])),
                    Math.max(Math.max(u[0], u[1]), Math.max(u[2], u[3])),
                    Math.max(Math.max(v[0], v[1]), Math.max(v[2], v[3])),
                    shaded(tint, brightness));
            entries.add(new Entry(face, x, y, z, (z[0] + z[1] + z[2] + z[3]) * 0.25f));
        }

        // painter's order from what actually overlaps on screen: centre depths alone put the cactus' inset sides
        // over its overhanging top. ties go to the farther face, cycles fall back to the farthest remaining one
        List<Face> faces() {
            int count = entries.size();
            if (count < 2 || entries.getFirst().x() == null) return entries.stream().map(Entry::face).toList();
            List<List<Integer>> inFront = new ArrayList<>(count);
            for (int i = 0; i < count; i++) inFront.add(new ArrayList<>());
            int[] behindCount = new int[count];
            for (int i = 0; i < count; i++) {
                for (int j = i + 1; j < count; j++) {
                    int order = order(entries.get(i), entries.get(j));
                    if (order < 0) {
                        inFront.get(i).add(j);
                        behindCount[j]++;
                    } else if (order > 0) {
                        inFront.get(j).add(i);
                        behindCount[i]++;
                    }
                }
            }
            List<Face> sorted = new ArrayList<>(count);
            boolean[] painted = new boolean[count];
            for (int step = 0; step < count; step++) {
                int next = farthest(painted, behindCount, true);
                if (next < 0) next = farthest(painted, behindCount, false);
                painted[next] = true;
                sorted.add(entries.get(next).face());
                for (int later : inFront.get(next)) behindCount[later]--;
            }
            return List.copyOf(sorted);
        }

        private int farthest(boolean[] painted, int[] behindCount, boolean unblockedOnly) {
            int best = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (painted[i] || (unblockedOnly && behindCount[i] > 0)) continue;
                if (best < 0 || entries.get(i).depth() < entries.get(best).depth()) best = i;
            }
            return best;
        }

        // -1 when first is behind second where they overlap, 1 for the reverse, 0 when they do not overlap;
        // coplanar overlaps (grass side and its overlay) keep model order
        private static int order(Entry first, Entry second) {
            float[][] overlap = overlap(first, second);
            if (overlap.length < 3 || Math.abs(area(overlap)) < 1e-6f) return 0;
            float cx = 0f;
            float cy = 0f;
            for (float[] point : overlap) {
                cx += point[0] / overlap.length;
                cy += point[1] / overlap.length;
            }
            float firstZ = planeDepth(first, cx, cy);
            float secondZ = planeDepth(second, cx, cy);
            if (Math.abs(firstZ - secondZ) < 1e-5f) return -1;
            return firstZ < secondZ ? -1 : 1;
        }

        // sutherland-hodgman: the first quad clipped to the second, both convex
        private static float[][] overlap(Entry first, Entry second) {
            float[][] polygon = counterClockwise(first);
            float[][] clip = counterClockwise(second);
            for (int edge = 0; edge < clip.length && polygon.length > 0; edge++) {
                float[] from = clip[edge];
                float[] to = clip[(edge + 1) % clip.length];
                List<float[]> kept = new ArrayList<>();
                for (int i = 0; i < polygon.length; i++) {
                    float[] p = polygon[i];
                    float[] q = polygon[(i + 1) % polygon.length];
                    float sideP = side(from, to, p);
                    float sideQ = side(from, to, q);
                    if (sideP >= 0f) kept.add(p);
                    if ((sideP >= 0f) != (sideQ >= 0f)) {
                        float t = sideP / (sideP - sideQ);
                        kept.add(new float[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t});
                    }
                }
                polygon = kept.toArray(new float[0][]);
            }
            return polygon;
        }

        private static float[][] counterClockwise(Entry entry) {
            float[][] points = new float[4][];
            for (int i = 0; i < 4; i++) points[i] = new float[]{entry.x()[i], entry.y()[i]};
            if (area(points) < 0f) {
                float[] swap = points[1];
                points[1] = points[3];
                points[3] = swap;
            }
            return points;
        }

        private static float side(float[] from, float[] to, float[] point) {
            return (to[0] - from[0]) * (point[1] - from[1]) - (to[1] - from[1]) * (point[0] - from[0]);
        }

        private static float area(float[][] polygon) {
            float sum = 0f;
            for (int i = 0; i < polygon.length; i++) {
                float[] p = polygon[i];
                float[] q = polygon[(i + 1) % polygon.length];
                sum += p[0] * q[1] - q[0] * p[1];
            }
            return sum * 0.5f;
        }

        // the face's plane read at a screen point, from corners 0, 1 and 3
        private static float planeDepth(Entry entry, float x, float y) {
            float[] px = entry.x();
            float[] py = entry.y();
            float[] pz = entry.z();
            float ax = px[1] - px[0];
            float ay = py[1] - py[0];
            float az = pz[1] - pz[0];
            float bx = px[3] - px[0];
            float by = py[3] - py[0];
            float bz = pz[3] - pz[0];
            float nx = ay * bz - az * by;
            float ny = az * bx - ax * bz;
            float nz = ax * by - ay * bx;
            if (Math.abs(nz) < 1e-9f) return entry.depth();
            return pz[0] - (nx * (x - px[0]) + ny * (y - py[0])) / nz;
        }
    }
}
