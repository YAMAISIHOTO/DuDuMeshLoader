package com.example.dudumeshloader.deco;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 存放「方块 index 的 {@code collision} 选项 → 碰撞档位」映射。
 *
 * <p>该映射由 {@code BlockIndexCollisionMixin} 在 TACZ 加载 index JSON（{@code JsonDataManager#apply}）时填充。
 * 主键为资源文件路徑（{@code index/blocks/<文件名>.json} → {@code <namespace>:<文件名>}），
 * 即 {@code GunSmithTableItem} 写入物品的 {@code BlockId}；同时按 JSON 内的 {@code id} 字段备份一份。
 * 由于反序列化发生在服务端/集成服务端线程，而注册表为静态共享映射，
 * 客户端放置方块时即可反查到对应碰撞档位。</p>
 */
public final class DecoCollisionRegistry {
    private static final Map<ResourceLocation, DecoCollisionType> COLLISION_BY_ID = new ConcurrentHashMap<>();

    private DecoCollisionRegistry() {
    }

    public static void put(ResourceLocation id, DecoCollisionType type) {
        if (id != null && type != null) {
            COLLISION_BY_ID.put(id, type);
        }
    }

    public static DecoCollisionType get(ResourceLocation id) {
        if (id == null) {
            return DecoCollisionType.SINGLE_A;
        }
        return COLLISION_BY_ID.getOrDefault(id, DecoCollisionType.SINGLE_A);
    }
}
