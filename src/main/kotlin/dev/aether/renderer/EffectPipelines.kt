package dev.aether.renderer

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.DepthStencilState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.BlendFactor
import com.mojang.renderpearl.api.pipeline.CompareOp
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.vertex.VertexFormat
import net.minecraft.resources.Identifier

// world and post effects as mc pipelines, so they compile for whichever backend is running
// unregistered on purpose: a shader that fails only disables its effect instead of mc's resource reload
class EffectPipelines private constructor() {
    companion object {
        private val SKYBOX_INFO = BindGroupLayout.builder()
            .withUniform("SkyboxInfo", UniformType.UNIFORM_BUFFER).build()
        private val RIPPLE_INPUTS = BindGroupLayout.builder()
            .withUniform("RippleInfo", UniformType.UNIFORM_BUFFER).withUniform("Scene", UniformType.COMBINED_IMAGE_SAMPLER).build()
        private val COSMETIC_INFO = BindGroupLayout.builder()
            .withUniform("CosmeticInfo", UniformType.UNIFORM_BUFFER).build()

        // matches CosmeticMesh: position, uv, float rgba and (material, age, seed)
        @JvmField
        val COSMETIC_FORMAT: VertexFormat = VertexFormat.builder(0)
            .addAttribute("Position", 0, GpuFormat.RGB32_FLOAT)
            .addAttribute("UV", 0, GpuFormat.RG32_FLOAT)
            .addAttribute("Color", 0, GpuFormat.RGBA32_FLOAT)
            .addAttribute("Effect", 0, GpuFormat.RGB32_FLOAT)
            .build()

        @JvmField
        val SKYBOX: RenderPipeline = RenderPipeline.builder()
            .withLocation(id("pipeline/skybox"))
            .withVertexShader(id("core/skybox"))
            .withFragmentShader(id("core/skybox"))
            .withBindGroupLayout(SKYBOX_INFO)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build()

        @JvmField
        val RIPPLE: RenderPipeline = RenderPipeline.builder()
            .withLocation(id("pipeline/ripple"))
            .withVertexShader(id("core/fullscreen"))
            .withFragmentShader(id("core/ripple"))
            .withBindGroupLayout(RIPPLE_INPUTS)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build()

        // 26.2 renders the world with reversed z, so nearer fragments have the larger depth
        @JvmField
        val COSMETIC_OPAQUE: RenderPipeline = RenderPipeline.builder()
            .withLocation(id("pipeline/cosmetic_opaque"))
            .withVertexShader(id("core/cosmetic_world"))
            .withFragmentShader(id("core/cosmetic_world"))
            .withBindGroupLayout(COSMETIC_INFO)
            .withVertexBinding(0, COSMETIC_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true))
            .build()

        @JvmField
        val COSMETIC_ADDITIVE: RenderPipeline = RenderPipeline.builder()
            .withLocation(id("pipeline/cosmetic_additive"))
            .withVertexShader(id("core/cosmetic_world"))
            .withFragmentShader(id("core/cosmetic_world"))
            .withBindGroupLayout(COSMETIC_INFO)
            .withVertexBinding(0, COSMETIC_FORMAT)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .withColorTargetState(ColorTargetState(
                BlendFunction(BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE)))
            .withDepthStencilState(DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
            .build()

        private fun id(path: String): Identifier {
            return Identifier.fromNamespaceAndPath("aether", path)
        }

        @JvmStatic
        fun usable(pipeline: RenderPipeline): Boolean {
            return RenderSystem.getCompiledPipeline(pipeline) != null
        }
    }
}