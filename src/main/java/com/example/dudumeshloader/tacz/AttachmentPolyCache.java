package com.example.dudumeshloader.tacz;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 普通（非 mixin）工具类：按 geo.json 路径缓存已构建的配件 poly_mesh 模型。
 *
 * <p>TACZ 的 {@code checkTextureAndModel}/{@code checkLod} 会在 mixin 之前把 {@code attachmentModel}/
 * {@code lodModel} 重置成普通 {@link com.tacz.guns.client.model.BedrockAttachmentModel}，所以不能在
 * mixin 里每次 {@code new TaczPolyMeshAttachmentModel(...)} —— 那会每帧重解析 geo.json（上万顶点 +
 * CharmCompat 注入 + ShaderStateTracker 注册），掏出配件即卡死。按 geoPath 缓存后，每个 geo.json
 * 整个会话只解析一次，后续只复用实例并刷新纹理。</p>
 *
 * <p>刻意放在 mixin 之外：{@code @Mixin} 类不能被游戏代码直接引用，直接调用其静态方法会让 Mixin
 * 把该类判定为 invalid → {@code NoClassDefFoundError}。</p>
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
