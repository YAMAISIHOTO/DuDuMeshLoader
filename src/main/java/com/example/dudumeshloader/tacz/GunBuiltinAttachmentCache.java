package com.example.dudumeshloader.tacz;

import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * 内置附件（枪型自带默认附件，如默认铁瞄/默认枪托）解析结果的缓存。
 *
 * <p>内置附件定义在枪型数据（{@code CommonGunIndex -> GunData.getBuiltInAttachments}）里，
 * 与具体枪物品的 NBT 无关，属于「枪型级别」的稳定数据。但 TaCZ 在
 * {@code GunItemDataAccessor.getBuiltinAttachment} 里每帧都
 * {@code AttachmentItemBuilder.build()} 新建一个 ItemStack，产生无谓的对象分配与 NBT 读写。
 * 这里按 {@code (gunId, type)} 缓存，命中时只 {@code copy()} 返回。</p>
 *
 * <p>资源重载（F3+T）时由 {@code DecoClientRenderers} 调用 {@link #clear()} 失效。</p>
 */
public final class GunBuiltinAttachmentCache {
    private static final Map<ResourceLocation, Map<AttachmentType, ItemStack>> CACHE = new HashMap<>();

    private GunBuiltinAttachmentCache() {
    }

    @Nullable
    public static ItemStack get(ResourceLocation gunId, AttachmentType type) {
        if (gunId == null) {
            return null;
        }
        Map<AttachmentType, ItemStack> perType = CACHE.get(gunId);
        return perType == null ? null : perType.get(type);
    }

    public static void put(ResourceLocation gunId, AttachmentType type, ItemStack stack) {
        if (gunId == null) {
            return;
        }
        CACHE.computeIfAbsent(gunId, k -> new EnumMap<>(AttachmentType.class)).put(type, stack);
    }

    public static void clear() {
        CACHE.clear();
    }
}
