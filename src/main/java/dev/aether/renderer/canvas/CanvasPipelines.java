package dev.aether.renderer.canvas;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public final class CanvasPipelines {
    // vanilla gui shaders, but triangles so arbitrary tessellated paths can go in one draw; culling is
    // off because path winding follows the shape. unregistered, so it can never fail mc's resource reload
    public static final RenderPipeline TEXTURED_TRIANGLES = RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("aether", "pipeline/canvas_textured_triangles"))
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .withCull(false)
            .build();

    private CanvasPipelines() {}
}
