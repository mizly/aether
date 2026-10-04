package dev.aether.renderer;

import net.minecraft.client.resources.model.cuboid.CuboidFace;
import net.minecraft.client.resources.model.cuboid.FaceBakery;
import net.minecraft.core.Direction;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IsoBlockPainterTest {

    // icons.md 11.4: the block.json gui projection for a slot of size 1
    private static final float A = 0.441942f;
    private static final float B = 0.220971f;
    private static final float H = 0.541266f;
    private static final float CX = 0.5f;
    private static final float CY = 0.450338f;

    @Test
    void cubeFacesMatchTheVanillaGuiProjection() {
        List<IsoBlockPainter.Face> faces = IsoBlockPainter.faces(new McIcon.Block("top", "left", "right", 0f));
        assertEquals(3, faces.size());
        assertAffine(face(faces, "top"), -A, B, -A, -B, CX + A, CY - B);
        assertAffine(face(faces, "left"), A, B, 0f, H, CX - A, CY - B);
        assertAffine(face(faces, "right"), A, -B, 0f, H, CX, CY);
        for (IsoBlockPainter.Face face : faces) {
            assertEquals(0f, face.u0(), 1e-6f);
            assertEquals(0f, face.v0(), 1e-6f);
            assertEquals(1f, face.u1(), 1e-6f);
            assertEquals(1f, face.v1(), 1e-6f);
        }
    }

    @Test
    void cubeShadingMatchesTheGuiLightRig() {
        List<IsoBlockPainter.Face> faces = IsoBlockPainter.faces(new McIcon.Block("top", "left", "right", 0f));
        assertEquals(0xFFFFFFFF, face(faces, "top").color());
        assertEquals(0xFFA6A6A6, face(faces, "left").color(), "east face at 0.6505");
        assertEquals(0xFF666666, face(faces, "right").color(), "north face at ambient 0.4");
        var pose = IsoBlockPainter.guiPose(IsoBlockPainter.BLOCK_GUI).last();
        assertEquals(1f, IsoBlockPainter.shade(pose.transformNormal(new Vector3f(0, 1, 0), new Vector3f()), false), 1e-4f);
        assertEquals(0.6505f, IsoBlockPainter.shade(pose.transformNormal(new Vector3f(1, 0, 0), new Vector3f()), false), 1e-4f);
        assertEquals(0.4f, IsoBlockPainter.shade(pose.transformNormal(new Vector3f(0, 0, -1), new Vector3f()), false), 1e-4f);
    }

    @Test
    void cubeSilhouetteMatchesTheSixteenPixelSlot() {
        float[] bounds = bounds(IsoBlockPainter.faces(new McIcon.Block("top", "left", "right", 0f)));
        assertEquals(0.93f, bounds[0] * 16f, 0.01f);
        assertEquals(0.13f, bounds[1] * 16f, 0.01f);
        assertEquals(15.07f, bounds[2] * 16f, 0.01f);
        assertEquals(15.87f, bounds[3] * 16f, 0.01f);
    }

    @Test
    void cactusInsetsItsSidesUnderTheOverhangingTop() {
        List<IsoBlockPainter.Face> faces = IsoBlockPainter.faces(new McIcon.Block("top", "left", "right", 1f / 16f));
        assertEquals("top", faces.getLast().texture(), "the top overhangs the inset sides, so it paints last");
        assertAffine(face(faces, "left"), A, B, 0f, H, CX - A + A / 16f, CY - B - B / 16f);
        assertAffine(face(faces, "right"), A, -B, 0f, H, CX - A / 16f, CY - B / 16f);
    }

    @Test
    void playerHeadShowsTheSkinFaceOnTheRightUnmirrored() {
        List<IsoBlockPainter.Face> faces = IsoBlockPainter.faces(new McIcon.Head("skin"));
        assertEquals(6, faces.size(), "three base faces and three hat faces");
        IsoBlockPainter.Face face = faces.stream()
                .filter(f -> Math.abs(f.u0() - 8f / 64f) < 1e-5f && Math.abs(f.v0() - 8f / 64f) < 1e-5f)
                .findFirst().orElseThrow();
        assertEquals(0xFF666666, face.color(), "the face is the darker right side");
        assertPoint(face, 8f / 64f, 8f / 64f, 8f / 16f, 7.8284f / 16f);
        assertPoint(face, 16f / 64f, 8f / 64f, 13.6569f / 16f, 5f / 16f);
        assertPoint(face, 8f / 64f, 16f / 64f, 8f / 16f, 14.7566f / 16f);
        assertTrue(faces.indexOf(face) < 3, "base faces paint before the hat layer");
    }

    @Test
    void coplanarOverlaysPaintAfterTheirBase() {
        List<IsoBlockPainter.Face> faces = IsoBlockPainter.faces(new McIcon.Model("minecraft:block/grass_block",
                List.of(McIcons.GRASS_TINT)));
        int side = indexOf(faces, "minecraft:textures/block/grass_block_side.png");
        int overlay = indexOf(faces, "minecraft:textures/block/grass_block_side_overlay.png");
        assertTrue(side >= 0 && overlay > side);
        IsoBlockPainter.Face top = faces.get(indexOf(faces, "minecraft:textures/block/grass_block_top.png"));
        assertEquals(McIcons.GRASS_TINT, top.color(), "the top is tinted at full light");
    }

    @Test
    void defaultUvsMatchFaceBakery() throws Exception {
        Method vanilla = FaceBakery.class.getDeclaredMethod("defaultFaceUV", org.joml.Vector3fc.class,
                org.joml.Vector3fc.class, Direction.class);
        vanilla.setAccessible(true);
        Vector3f from = new Vector3f(1f, 2f, 3f);
        Vector3f to = new Vector3f(9f, 13f, 15f);
        for (Direction direction : Direction.values()) {
            assertEquals(vanilla.invoke(null, from, to, direction), IsoBlockPainter.defaultUvs(from, to, direction),
                    direction.toString());
        }
    }

    @Test
    void flatSpritesFillTheIconInLayerOrder() {
        List<IsoBlockPainter.Face> faces = IsoBlockPainter.faces(new McIcon.Layered(List.of(
                new McIcon.Sprite("base"), new McIcon.Sprite("overlay", 0xFF102030))));
        assertEquals(List.of("base", "overlay"), faces.stream().map(IsoBlockPainter.Face::texture).toList());
        assertAffine(faces.get(1), 1f, 0f, 0f, 1f, 0f, 0f);
        assertEquals(0xFF102030, faces.get(1).color());
    }

    private static IsoBlockPainter.Face face(List<IsoBlockPainter.Face> faces, String texture) {
        return faces.get(indexOf(faces, texture));
    }

    private static int indexOf(List<IsoBlockPainter.Face> faces, String texture) {
        for (int i = 0; i < faces.size(); i++) if (faces.get(i).texture().equals(texture)) return i;
        return -1;
    }

    private static void assertAffine(IsoBlockPainter.Face face, float a, float b, float c, float d, float e, float f) {
        float[] actual = {face.a(), face.b(), face.c(), face.d(), face.e(), face.f()};
        float[] expected = {a, b, c, d, e, f};
        for (int i = 0; i < 6; i++) assertEquals(expected[i], actual[i], 2e-5f, face.texture() + " term " + i);
    }

    private static void assertPoint(IsoBlockPainter.Face face, float u, float v, float x, float y) {
        assertEquals(x, face.a() * u + face.c() * v + face.e(), 1e-4f);
        assertEquals(y, face.b() * u + face.d() * v + face.f(), 1e-4f);
    }

    // min x, min y, max x, max y over every face's corners, in icon units
    private static float[] bounds(List<IsoBlockPainter.Face> faces) {
        float[] bounds = {Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (IsoBlockPainter.Face face : faces) {
            for (float u : new float[]{face.u0(), face.u1()}) {
                for (float v : new float[]{face.v0(), face.v1()}) {
                    float x = face.a() * u + face.c() * v + face.e();
                    float y = face.b() * u + face.d() * v + face.f();
                    bounds[0] = Math.min(bounds[0], x);
                    bounds[1] = Math.min(bounds[1], y);
                    bounds[2] = Math.max(bounds[2], x);
                    bounds[3] = Math.max(bounds[3], y);
                }
            }
        }
        return bounds;
    }
}
