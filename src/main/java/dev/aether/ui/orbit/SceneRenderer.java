package dev.aether.ui.orbit;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.aether.mixin.AccessorGlDevice;
import dev.aether.mixin.AccessorGpuDevice;
import dev.aether.util.AetherResources;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

// draws the menu's own little world into a framebuffer of its own, copied over the level just before the gui, so
// nothing the game or a renderer mod draws later can black it out. raw gl, every touched state put back after
final class SceneRenderer implements AutoCloseable {
    // what one frame needs beyond the mesh: where the figure stands and how the sky looks
    record Frame(double anchorX, double anchorY, double anchorZ, float yaw, SceneClone.Buffer figure,
                 Identifier skin, int zenith, int horizon, List<SceneActors.Draw> actors, SceneClone.Buffer blocks) {
    }

    private final Matrix4f projection = new Matrix4f();
    private int program;

    private int matrixUniform, offsetUniform, fogCenterUniform, fogRangeUniform, fogColorUniform, alphaUniform, samplerUniform;
    private int solidUniform;
    private int fbo, colorBuffer, depthBuffer, width, height;
    private boolean drawn;
    private final int[] vao = new int[3];
    private final int[] vbo = new int[3];
    private int atlasSampler;
    private int skinSampler;
    private int white;
    private SceneClone.Mesh uploaded;
    private boolean failed;

    boolean failed() {
        return failed;
    }

    int framebuffer() {
        return fbo;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    // false when nothing was drawn, so the caller falls back to the panels over the live level
    boolean draw(SceneClone.Mesh mesh, Frame frame) {
        drawn = false;
        if (failed || mesh == null) return false;
        Minecraft client = Minecraft.getInstance();
        var target = client.getMainRenderTarget();
        var camera = client.gameRenderer.getMainCamera();
        var eye = camera.position();
        camera.getViewRotationProjectionMatrix(projection);
        GlSnapshot saved = GlSnapshot.capture();
        try {
            if (program == 0) initialize();
            if (uploaded != mesh) upload(mesh);
            resize(target.width, target.height);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            GL11.glViewport(0, 0, width, height);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glColorMask(true, true, true, true);
            GL11.glDepthMask(true);
            GL11.glClearColor(r(frame.horizon()), g(frame.horizon()), b(frame.horizon()), 1f);
            GL11.glClearDepth(1.0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL20.glUseProgram(program);
            GL20.glUniform1i(samplerUniform, 0);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            float ox = (float) (mesh.originX() - eye.x), oy = (float) (mesh.originY() - eye.y), oz = (float) (mesh.originZ() - eye.z);
            int atlas = texture(client, TextureAtlas.LOCATION_BLOCKS);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                sky(stack, frame);
                world(stack, frame, mesh, ox, oy, oz);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDisable(GL11.GL_BLEND);
                GL20.glUniform1f(alphaUniform, 0.1f);
                GL20.glUniform1f(solidUniform, 1f);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
                GL33C.glBindSampler(0, atlasSampler);
                drawArrays(0, mesh.solidCount());

                if (mesh.waterCount() > 0) {
                    world(stack, frame, mesh, ox, oy, oz);
                    // water is a plain tinted sheet: its animated sprite sampled from far off can come out black
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, white);
                    GL33C.glBindSampler(0, skinSampler);
                    GL20.glUniform1f(alphaUniform, 0.01f);
                    GL20.glUniform1f(solidUniform, 0f);
                    GL11.glEnable(GL11.GL_DEPTH_TEST);
                    GL11.glEnable(GL11.GL_BLEND);
                    GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                    GL11.glDepthMask(false);
                    drawArrays(1, mesh.waterCount());
                }

                // the panels hang clear of the farm: only the figure, in a fresh depth buffer, may stand in front of them
                GL11.glDepthMask(true);
                GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
                if (frame.figure() != null && frame.figure().count > 0) {
                    int skin = texture(client, frame.skin());
                    if (skin != 0) {
                        GL20.glUniformMatrix4fv(matrixUniform, false, projection.get(stack.mallocFloat(16)));
                        GL11.glEnable(GL11.GL_DEPTH_TEST);
                        GL11.glDisable(GL11.GL_BLEND);
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, skin);
                        GL33C.glBindSampler(0, skinSampler);
                        GL20.glUniform3f(offsetUniform, 0f, 0f, 0f);
                        GL20.glUniform2f(fogRangeUniform, 1e8f, 2e8f);
                        GL20.glUniform1f(alphaUniform, 0.1f);
                        GL20.glUniform1f(solidUniform, 1f);
                        ByteBuffer data = frame.figure().finish();
                        GL30.glBindVertexArray(vao[2]);
                        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[2]);
                        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
                        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, frame.figure().count);
                    }
                }
                // the farm's actors, each model with its own entity texture, then their blocks from the atlas
                GL20.glUniformMatrix4fv(matrixUniform, false, projection.get(stack.mallocFloat(16)));
                GL20.glUniform3f(offsetUniform, 0f, 0f, 0f);
                GL20.glUniform2f(fogRangeUniform, 1e8f, 2e8f);
                GL20.glUniform1f(alphaUniform, 0.1f);
                GL20.glUniform1f(solidUniform, 1f);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDisable(GL11.GL_BLEND);
                if (frame.actors() != null) {
                    for (SceneActors.Draw draw : frame.actors()) {
                        int tex = texture(client, draw.texture());
                        if (tex == 0) continue;
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
                        GL33C.glBindSampler(0, skinSampler);
                        stream(draw.buffer());
                    }
                }
                if (frame.blocks() != null && frame.blocks().count > 0) {
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
                    GL33C.glBindSampler(0, atlasSampler);
                    stream(frame.blocks());
                }
            }
            drawn = true;
        } catch (RuntimeException error) {
            close();
            failed = true;
            System.err.println("[Aether] Orbit scene renderer disabled: " + error.getMessage());
        } finally {
            saved.restore();
        }
        return drawn;
    }

    // copies this frame's picture over the main target, right before the gui draws on top
    void present() {
        if (!drawn || failed || fbo == 0) return;
        drawn = false;
        var target = Minecraft.getInstance().getMainRenderTarget();
        if (!(target.getColorTexture() instanceof GlTexture color) || !(target.getDepthTexture() instanceof GlTexture)) return;
        var access = ((AccessorGlDevice) ((AccessorGpuDevice) RenderSystem.getDevice()).aether$getBackend())
                .aether$directStateAccess();
        int main = color.getFbo(access, target.getDepthTexture());
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, main);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, target.width, target.height,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, drawFbo);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    private void resize(int w, int h) {
        if (fbo != 0 && w == width && h == height) return;
        freeTarget();
        int previous = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        try {
            colorBuffer = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, colorBuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL11.GL_RGBA8, w, h);
            depthBuffer = GL30.glGenRenderbuffers();
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depthBuffer);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, w, h);
        } finally {
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previous);
        }
        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
        GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, colorBuffer);
        GL30.glFramebufferRenderbuffer(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depthBuffer);
        int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
        width = w;
        height = h;
        if (status != GL30.GL_FRAMEBUFFER_COMPLETE) throw new IllegalStateException("scene framebuffer incomplete: " + status);
    }

    private void freeTarget() {
        if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
        if (colorBuffer != 0) GL30.glDeleteRenderbuffers(colorBuffer);
        if (depthBuffer != 0) GL30.glDeleteRenderbuffers(depthBuffer);
        fbo = colorBuffer = depthBuffer = width = height = 0;
    }

    private void world(MemoryStack stack, Frame frame, SceneClone.Mesh mesh, float ox, float oy, float oz) {
        GL20.glUniformMatrix4fv(matrixUniform, false, projection.get(stack.mallocFloat(16)));
        GL20.glUniform3f(offsetUniform, ox, oy, oz);
        GL20.glUniform3f(fogColorUniform, r(frame.horizon()), g(frame.horizon()), b(frame.horizon()));
        GL20.glUniform2f(fogCenterUniform, (float) (frame.anchorX() - mesh.originX()), (float) (frame.anchorZ() - mesh.originZ()));
        GL20.glUniform2f(fogRangeUniform, mesh.radius() - 14f, mesh.radius() - 1f);
    }

    // a vertical gradient over the whole screen as the backdrop, leaving the depth buffer clear for the farm
    private void sky(MemoryStack stack, Frame frame) {
        GL20.glUniformMatrix4fv(matrixUniform, false, new Matrix4f().get(stack.mallocFloat(16)));
        GL20.glUniform3f(offsetUniform, 0f, 0f, 0f);
        GL20.glUniform2f(fogRangeUniform, 1e8f, 2e8f);
        GL20.glUniform1f(alphaUniform, 0f);
        GL20.glUniform1f(solidUniform, 1f);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDepthFunc(GL11.GL_ALWAYS);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, white);
        GL33C.glBindSampler(0, skinSampler);
        SceneClone.Buffer sky = new SceneClone.Buffer(6);
        int top = SceneClone.rgba(frame.zenith(), 1f, 255), bottom = SceneClone.rgba(frame.horizon(), 1f, 255);
        sky.vertex(-1, 1, 1f, 0, 0, top);
        sky.vertex(1, 1, 1f, 1, 0, top);
        sky.vertex(1, -1, 1f, 1, 1, bottom);
        sky.vertex(-1, 1, 1f, 0, 0, top);
        sky.vertex(1, -1, 1f, 1, 1, bottom);
        sky.vertex(-1, -1, 1f, 0, 1, bottom);
        ByteBuffer data = sky.finish();
        try {
            GL30.glBindVertexArray(vao[2]);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[2]);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 6);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(data);
            GL11.glDepthMask(true);
        }
    }

    private void stream(SceneClone.Buffer buffer) {
        int count = buffer.count;
        ByteBuffer data = buffer.finish();
        GL30.glBindVertexArray(vao[2]);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[2]);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
    }

    private void drawArrays(int index, int count) {
        if (count <= 0) return;
        GL30.glBindVertexArray(vao[index]);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, count);
    }

    private void upload(SceneClone.Mesh mesh) {
        GL30.glBindVertexArray(vao[0]);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[0]);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh.solid(), GL15.GL_STATIC_DRAW);
        GL30.glBindVertexArray(vao[1]);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[1]);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh.water(), GL15.GL_STATIC_DRAW);
        uploaded = mesh;
    }

    private static int texture(Minecraft client, Identifier id) {
        try {
            if (client.getTextureManager().getTexture(id).getTexture() instanceof GlTexture gl) return gl.glId();
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    private void initialize() {
        int vertex = 0, fragment = 0;
        try {
            vertex = compile(GL20.GL_VERTEX_SHADER, "orbit_scene.vsh");
            fragment = compile(GL20.GL_FRAGMENT_SHADER, "orbit_scene.fsh");
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vertex);
            GL20.glAttachShader(program, fragment);
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetProgramInfoLog(program));
            }
        } finally {
            if (vertex != 0) GL20.glDeleteShader(vertex);
            if (fragment != 0) GL20.glDeleteShader(fragment);
        }
        matrixUniform = GL20.glGetUniformLocation(program, "ViewProjection");
        offsetUniform = GL20.glGetUniformLocation(program, "Offset");
        fogCenterUniform = GL20.glGetUniformLocation(program, "FogCenter");
        fogRangeUniform = GL20.glGetUniformLocation(program, "FogRange");
        fogColorUniform = GL20.glGetUniformLocation(program, "FogColor");
        alphaUniform = GL20.glGetUniformLocation(program, "AlphaCut");
        samplerUniform = GL20.glGetUniformLocation(program, "Sampler");
        solidUniform = GL20.glGetUniformLocation(program, "Solid");
        for (int i = 0; i < 3; i++) {
            vao[i] = GL30.glGenVertexArrays();
            vbo[i] = GL15.glGenBuffers();
            GL30.glBindVertexArray(vao[i]);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo[i]);
            GL20.glEnableVertexAttribArray(0);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, SceneClone.STRIDE, 0L);
            GL20.glEnableVertexAttribArray(1);
            GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, SceneClone.STRIDE, 12L);
            GL20.glEnableVertexAttribArray(2);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, SceneClone.STRIDE, 20L);
        }
        atlasSampler = sampler(GL11.GL_NEAREST_MIPMAP_LINEAR);
        skinSampler = sampler(GL11.GL_NEAREST);
        white = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, white);
        ByteBuffer px = org.lwjgl.system.MemoryUtil.memAlloc(4).put(0, (byte) -1).put(1, (byte) -1).put(2, (byte) -1).put(3, (byte) -1);
        try {
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(px);
        }
    }

    private static int sampler(int minFilter) {
        int id = GL33C.glGenSamplers();
        GL33C.glSamplerParameteri(id, GL11.GL_TEXTURE_MIN_FILTER, minFilter);
        GL33C.glSamplerParameteri(id, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL33C.glSamplerParameteri(id, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33C.glSamplerParameteri(id, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return id;
    }

    private static int compile(int type, String name) {
        int shader = GL20.glCreateShader(type);
        try (var stream = AetherResources.open("/assets/aether/shaders/" + name)) {
            if (stream == null) throw new IllegalStateException("missing " + name);
            GL20.glShaderSource(shader, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            GL20.glCompileShader(shader);
            if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
                throw new IllegalStateException(GL20.glGetShaderInfoLog(shader));
            }
            return shader;
        } catch (IOException | RuntimeException error) {
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("cannot compile " + name, error);
        }
    }

    private static float r(int argb) {
        return ((argb >> 16) & 255) / 255f;
    }

    private static float g(int argb) {
        return ((argb >> 8) & 255) / 255f;
    }

    private static float b(int argb) {
        return (argb & 255) / 255f;
    }

    @Override
    public void close() {
        if (program != 0) GL20.glDeleteProgram(program);
        for (int i = 0; i < 3; i++) {
            if (vao[i] != 0) GL30.glDeleteVertexArrays(vao[i]);
            if (vbo[i] != 0) GL15.glDeleteBuffers(vbo[i]);
            vao[i] = vbo[i] = 0;
        }
        if (atlasSampler != 0) GL33C.glDeleteSamplers(atlasSampler);
        if (skinSampler != 0) GL33C.glDeleteSamplers(skinSampler);
        if (white != 0) GL11.glDeleteTextures(white);
        program = atlasSampler = skinSampler = white = 0;
        uploaded = null;
        freeTarget();
        drawn = false;
    }
}
