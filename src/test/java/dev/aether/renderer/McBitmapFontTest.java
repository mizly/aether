package dev.aether.renderer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.font.GlyphBitmap;
import com.mojang.blaze3d.font.GlyphInfo;
import com.mojang.blaze3d.font.GlyphProvider;
import com.mojang.blaze3d.font.UnbakedGlyph;
import dev.aether.util.AetherResources;
import net.minecraft.client.gui.font.glyphs.BakedGlyph;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class McBitmapFontTest {

    @Test
    void everyBitmapGlyphMatchesVanillasOwnProvider() throws Exception {
        JsonObject include = readJson("/assets/minecraft/font/include/default.json");
        int checked = 0;
        for (JsonElement element : include.getAsJsonArray("providers")) {
            JsonObject json = element.getAsJsonObject();
            List<int[]> rows = json.getAsJsonArray("chars").asList().stream()
                    .map(row -> row.getAsString().codePoints().toArray()).toList();
            BitmapProvider.Definition definition = new BitmapProvider.Definition(
                    Identifier.parse(json.get("file").getAsString()),
                    json.has("height") ? json.get("height").getAsInt() : 8,
                    json.get("ascent").getAsInt(), rows.toArray(int[][]::new));
            try (GlyphProvider vanilla = definition.unpack().left().orElseThrow().load(new ClasspathResources())) {
                for (int codepoint : vanilla.getSupportedGlyphs()) {
                    // the space provider comes first in the default font, so ascii's blank space cell never shows
                    if (codepoint == ' ') continue;
                    UnbakedGlyph expected = vanilla.getGlyph(codepoint);
                    McBitmapFont.Glyph glyph = McBitmapFont.table().glyphs().get(codepoint);
                    String name = "U+" + Integer.toHexString(codepoint);
                    assertNotNull(glyph, name);
                    assertEquals(expected.info().getAdvance(), glyph.advance(), name + " advance");
                    GlyphBitmap bitmap = bitmapOf(expected);
                    assertEquals(bitmap.getTop(), glyph.top(), name + " top");
                    assertEquals(bitmap.getRight() - bitmap.getLeft(), glyph.width() * glyph.pixelScale(), 1e-4f, name);
                    assertEquals(bitmap.getBottom() - bitmap.getTop(), glyph.height() * glyph.pixelScale(), 1e-4f, name);
                    checked++;
                }
            }
        }
        assertTrue(checked > 2000, "checked " + checked + " glyphs");
    }

    @Test
    void measuresLikeVanilla() {
        assertEquals(4f, McBitmapFont.width(" ", 1), "space comes from the space provider");
        assertEquals(2f, McBitmapFont.width("i", 1));
        assertEquals(3f, McBitmapFont.width("l", 1));
        assertEquals(6f, McBitmapFont.width("A", 1));
        assertEquals(78f, McBitmapFont.width("Configure Plots", 1));
        assertEquals(156f, McBitmapFont.width("Configure Plots", 2));
        assertEquals(27, McBitmapFont.lineHeight(3));
    }

    @Test
    void formattingCodesTakeNoSpaceButBoldAddsAPixelPerGlyph() {
        float plain = McBitmapFont.width("Plot - 5", 1);
        assertEquals(plain, McBitmapFont.width("§aPlot §7- §b5", 1));
        assertEquals(plain + 8f, McBitmapFont.width("§lPlot - 5", 1));
        assertEquals(plain, McBitmapFont.width("§l§aPlot - 5", 1), "a colour code clears bold, as in vanilla");
        assertEquals(plain + 4f, McBitmapFont.width("§a§lPlot§r - 5", 1));
        assertEquals(plain, McBitmapFont.width("§zPlot - 5§", 1), "unknown codes and a trailing § are skipped");
        assertEquals(plain, McBitmapFont.width("§APlot - 5", 1), "codes are case-insensitive");
    }

    @Test
    void literalModeDrawsTheSectionSign() {
        assertEquals(0f, McBitmapFont.width("§", 1));
        float section = McBitmapFont.widthLiteral("§", 1);
        assertTrue(section > 0f);
        assertEquals(section + McBitmapFont.width("aPlot", 1), McBitmapFont.widthLiteral("§aPlot", 1));
    }

    @Test
    void stripsCodes() {
        assertEquals("Plot - 5", McBitmapFont.strip("§aPlot §7- §b5"));
        assertEquals("plain", McBitmapFont.strip("plain"));
    }

    @Test
    void wrapsAtSpacesAndCarriesTheActiveStyle() {
        float width = McBitmapFont.width("§lLeft-click to", 1);
        assertEquals(List.of("§e§lLeft-click to", "§e§lconfigure"),
                McBitmapFont.wrap("§e§lLeft-click to configure", width, 1));
        assertEquals(List.of("§e§lLeft-click", "§e§lto configure"),
                McBitmapFont.wrap("§e§lLeft-click to configure", width - 1f, 1));
        assertEquals(List.of("§7a", "§7b"), McBitmapFont.wrap("§7a\nb", 100f, 1));
        List<String> forced = McBitmapFont.wrap("WWWWWWWW", McBitmapFont.width("WWW", 1), 1);
        assertEquals(List.of("WWW", "WWW", "WW"), forced);
        assertEquals(List.of("W"), McBitmapFont.wrap("W", 1f, 1), "a glyph wider than the line still makes progress");
        for (String line : McBitmapFont.wrap("§aThe quick brown fox jumps over the lazy dog", 60f, 1)) {
            assertTrue(McBitmapFont.width(line, 1) <= 60f, line);
        }
    }

    @Test
    void ellipsizesWithThreeDots() {
        assertEquals("Configure Plots", McBitmapFont.ellipsize("Configure Plots", 78f, 1));
        String cut = McBitmapFont.ellipsize("§aConfigure Plots", 40f, 1);
        assertTrue(cut.startsWith("§aConf") && cut.endsWith("..."), cut);
        assertTrue(McBitmapFont.width(cut, 1) <= 40f, cut);
        assertEquals("...", McBitmapFont.ellipsizeLiteral("Configure", 7f, 1));
    }

    private static GlyphBitmap bitmapOf(UnbakedGlyph glyph) {
        GlyphBitmap[] captured = new GlyphBitmap[1];
        glyph.bake(new UnbakedGlyph.Stitcher() {
            @Override
            public BakedGlyph stitch(GlyphInfo info, GlyphBitmap bitmap) {
                captured[0] = bitmap;
                return null;
            }

            @Override
            public BakedGlyph getMissing() {
                return null;
            }
        });
        return captured[0];
    }

    private static JsonObject readJson(String path) throws Exception {
        try (InputStream in = AetherResources.open(path)) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    // just enough of a resource manager for vanilla's provider loader to read the jar's textures
    private static final class ClasspathResources implements ResourceManager {
        @Override
        public Optional<Resource> getResource(Identifier location) {
            String path = "/assets/" + location.getNamespace() + "/" + location.getPath();
            return Optional.of(new Resource((PackResources) null, () -> AetherResources.open(path)));
        }

        @Override
        public Set<String> getNamespaces() {
            return Set.of("minecraft");
        }

        @Override
        public List<Resource> getResourceStack(Identifier location) {
            return getResource(location).stream().toList();
        }

        @Override
        public Map<Identifier, Resource> listResources(String path, Predicate<Identifier> filter) {
            return Map.of();
        }

        @Override
        public Map<Identifier, List<Resource>> listResourceStacks(String path, Predicate<Identifier> filter) {
            return Map.of();
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.empty();
        }
    }
}
