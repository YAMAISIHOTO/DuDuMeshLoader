package com.example.dudumeshloader.tacz;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 普通（非 mixin）工具类：按 geo.json 路径缓存已构建的配件 poly_mesh 模型。
 *
 * <p>关键修复：TACZ 的 {@code checkTextureAndModel}/{@code checkLod} 在 mixin 之前会把
 * {@code attachmentModel}/{@code lodModel} 字段重置成普通 {@link com.tacz.guns.client.model.BedrockAttachmentModel}，
 * 因此若每次都在 mixin 里 {@code new TaczPolyMeshAttachmentModel(...)}，则每帧/每次索引重建都会
 * 完整重解析 geo.json（上万顶点 + CharmCompat 注入 + ShaderStateTracker 注册），导致掏出配件即卡死。
 * 改为按 geoPath 缓存，整个会话内每个 geo.json 只解析一次，后续 mixin 触发只复用实例并刷新纹理。</p>
 *
 * <p>本类刻意放在 mixin 之外，因为 {@code @Mixin} 类不能被游戏代码直接引用
 * （直接调用其静态方法会触发 Mixin 将类判定为 invalid → NoClassDefFoundError）。</p>
 */
public final class AttachmentPolyCache {

    private static final Map<ResourceLocation, TaczPolyMeshAttachmentModel> MAIN_CACHE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, TaczPolyMeshAttachmentModel> LOD_CACHE = new ConcurrentHashMap<>();

    private AttachmentPolyCache() {}

    public static TaczPolyMeshAttachmentModel getMain(ResourceLocation geoPath) {
        return MAIN_CACHE.get(geoPath);
    }

    public static void putMain(ResourceLocation geoPath, TaczPolyMeshAttachmentModel model) {
        MAIN_CACHE.put(geoPath, model);
    }

    public static TaczPolyMeshAttachmentModel getLod(ResourceLocation geoPath) {
        return LOD_CACHE.get(geoPath);
    }

    public static void putLod(ResourceLocation geoPath, TaczPolyMeshAttachmentModel model) {
        LOD_CACHE.put(geoPath, model);
    }

    /** 资源重载（F3+T 等）时清空缓存，避免复用已失效的 geo 解析结果。 */
    public static void clear() {
        MAIN_CACHE.clear();
        LOD_CACHE.clear();
    }
}
