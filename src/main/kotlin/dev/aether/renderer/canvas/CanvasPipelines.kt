package dev.aether.renderer.canvas

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

class CanvasPipelines private constructor() {
    companion object {
        // vanilla gui shaders, but triangles so arbitrary tessellated paths can go in one draw; culling is
        // off because path winding follows the shape. unregistered, so it can never fail mc's resource reload
        @JvmField
        val TEXTURED_TRIANGLES: RenderPipeline = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("aether", "pipeline/canvas_textured_triangles"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build()
    }
}
