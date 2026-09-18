package com.example.dudumeshloader.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.Function;

/**
 * poly_mesh 专用三角形 RenderType。
 *
 * <p>cutout 状态复刻 SimpleBedrockModel 2.5.1；半透明状态则保留本模组
 * 既有的 entityTranslucentCull 行为，只把图元模式改为 TRIANGLES。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class PolyMeshRenderTypes extends RenderType {

    private static final Function<ResourceLocation, RenderType> CUTOUT_NO_CULL = Util.memoize(texture -> create(
            "tacz_poly_mesh_cutout",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.TRIANGLES,
            256,
            true,
            false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true)
    ));

    /**
     * TaCZ attachment 特殊节点专用：保持三角图元与逐角点法线，
     * 但正反面语义与原生 entityCutout 完全一致。
     */
    private static final Function<ResourceLocation, RenderType> CUTOUT_CULL = Util.memoize(texture -> create(
            "tacz_poly_mesh_attachment_cutout",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.TRIANGLES,
            256,
            true,
            false,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true)
    ));

    /** 第一人称闲置 sight 镜片：参与深度测试，但不向深度缓冲写入。 */
    private static final Function<ResourceLocation, RenderType> CUTOUT_CULL_COLOR_ONLY_TRIANGLES =
            Util.memoize(texture -> create(
                    "tacz_poly_mesh_idle_sight_cutout",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.TRIANGLES,
                    256,
                    true,
                    false,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setCullState(CULL)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(true)
            ));

    /** 与上面的三角层配套，供同一 ocular_sight 子树中的原生 cube 使用。 */
    private static final Function<ResourceLocation, RenderType> CUTOUT_CULL_COLOR_ONLY_QUADS =
            Util.memoize(texture -> create(
                    "tacz_cube_idle_sight_cutout",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256,
                    true,
                    false,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_CUTOUT_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(NO_TRANSPARENCY)
                            .setCullState(CULL)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(true)
            ));

    private static final Function<ResourceLocation, RenderType> TRANSLUCENT_CULL = Util.memoize(texture -> create(
            "tacz_poly_mesh_translucent_cull",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.TRIANGLES,
            256,
            true,
            true,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true)
    ));

    private static final Function<ResourceLocation, RenderType> TRANSLUCENT_NO_CULL = Util.memoize(texture -> create(
            "tacz_poly_mesh_translucent_no_cull",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.TRIANGLES,
            256,
            true,
            true,
            CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .createCompositeState(true)
    ));

    private PolyMeshRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                                int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                                Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    public static RenderType cutoutNoCull(ResourceLocation texture) {
        return CUTOUT_NO_CULL.apply(texture);
    }

    public static RenderType cutoutCull(ResourceLocation texture) {
        return CUTOUT_CULL.apply(texture);
    }

    public static RenderType cutoutCullColorOnlyTriangles(ResourceLocation texture) {
        return CUTOUT_CULL_COLOR_ONLY_TRIANGLES.apply(texture);
    }

    public static RenderType cutoutCullColorOnlyQuads(ResourceLocation texture) {
        return CUTOUT_CULL_COLOR_ONLY_QUADS.apply(texture);
    }

    public static RenderType translucentCull(ResourceLocation texture) {
        return TRANSLUCENT_CULL.apply(texture);
    }

    public static RenderType translucentNoCull(ResourceLocation texture) {
        return TRANSLUCENT_NO_CULL.apply(texture);
    }
}
