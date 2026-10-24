package dev.aether.renderer;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

// world and post effects as mc pipelines, so they compile for whichever backend is running
// unregistered on purpose: a shader that fails only disables its effect instead of mc's resource reload
public final class EffectPipelines {
    private static final BindGroupLayout SKYBOX_INFO = BindGroupLayout.builder()
            .withUniform("SkyboxInfo", UniformType.UNIFORM_BUFFER).build();
    private static final BindGroupLayout RIPPLE_INPUTS = BindGroupLayout.builder()
            .withUniform("RippleInfo", UniformType.UNIFORM_BUFFER).withSampler("Scene").build();
    private static final BindGroupLayout COSMETIC_INFO = BindGroupLayout.builder()
            .withUniform("CosmeticInfo", UniformType.UNIFORM_BUFFER).build();

    // matches CosmeticMesh: position, uv, float rgba and (material, age, seed)
    public static final VertexFormat COSMETIC_FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV", GpuFormat.RG32_FLOAT)
            .addAttribute("Color", GpuFormat.RGBA32_FLOAT)
            .addAttribute("Effect", GpuFormat.RGB32_FLOAT)
            .build();

    public static final RenderPipeline SKYBOX = RenderPipeline.builder()
            .withLocation(id("pipeline/skybox"))
            .withVertexShader(id("core/skybox"))
            .withFragmentShader(id("core/skybox"))
            .withBindGroupLayout(SKYBOX_INFO)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    public static final RenderPipeline RIPPLE = RenderPipeline.builder()
            .withLocation(id("pipeline/ripple"))
            .withVertexShader(id("core/fullscreen"))
            .withFragmentShader(id("core/ripple"))
            .withBindGroupLayout(RIPPLE_INPUTS)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build();

    // 26.2 renders the world with reversed z, so nearer fragments have the larger depth
    public static final RenderPipeline COSMETIC_OPAQUE = RenderPipeline.builder()
            .withLocation(id("pipeline/cosmetic_opaque"))
            .withVertexShader(id("core/cosmetic_world"))
            .withFragmentShader(id("core/cosmetic_world"))
            .withBindGroupLayout(COSMETIC_INFO)
            .withVertexBinding(0, COSMETIC_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .build();

    public static final RenderPipeline COSMETIC_ADDITIVE = RenderPipeline.builder()
            .withLocation(id("pipeline/cosmetic_additive"))
            .withVertexShader(id("core/cosmetic_world"))
            .withFragmentShader(id("core/cosmetic_world"))
            .withBindGroupLayout(COSMETIC_INFO)
            .withVertexBinding(0, COSMETIC_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(new ColorTargetState(
                    new BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE)))
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .build();

    private EffectPipelines() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("aether", path);
    }

    public static boolean usable(RenderPipeline pipeline) {
        return RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
    }
}
