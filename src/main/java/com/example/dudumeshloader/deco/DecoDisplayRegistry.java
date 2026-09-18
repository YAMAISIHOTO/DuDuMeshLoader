package com.example.dudumeshloader.deco;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 存放「方块 index → 显示文件（BlockDisplay）」映射。
 *
 * <p>由 {@code BlockIndexCollisionMixin} 在 TACZ 加载 index JSON 时，抓取其中的
 * {@code display} 选项（{@link ResourceLocation}）写入本注册表。该 RL 指向的
 * {@code BlockDisplay} 内含几何体位置（{@code model}），渲染器据此重建一个
 * {@code BedrockAnimatedModel} 以便驱动 TACZ 动画。</p>
 *
 * <p>键为 index 的<b>文件路径 key</b>（{@code <namespace>:<文件名>}，即物品 {@code BlockId}），
 * 与 {@link DecoCollisionRegistry} / {@link DecoMountBoneRegistry} 完全一致。</p>
 */
public final class DecoDisplayRegistry {
    private static final Map<ResourceLocation, ResourceLocation> REGISTRY = new HashMap<>();

    private DecoDisplayRegistry() {
    }

    public static void put(ResourceLocation key, ResourceLocation display) {
        if (key == null || display == null) {
            return;
        }
        REGISTRY.put(key, display);
    }

    /** 返回该 index 的显示文件 RL；未配置则返回 {@code null}。 */
    public static ResourceLocation get(ResourceLocation key) {
        return key == null ? null : REGISTRY.get(key);
    }
}
