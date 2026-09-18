package com.example.dudumeshloader.mixin;

import com.example.dudumeshloader.tacz.GunBuiltinAttachmentCache;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.api.item.builder.AttachmentItemBuilder;
import com.tacz.guns.api.item.nbt.GunItemDataAccessor;
import com.tacz.guns.resource.index.CommonGunIndex;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Map;

/**
 * 缓存内置附件解析，消除 TaCZ 渲染时每帧重建 ItemStack 的开销。
 *
 * <p>只覆盖 {@code getBuiltinAttachment} 这一个 default 方法：逻辑与原实现完全一致，
 * 额外按 {@code (gunId, type)} 查/存 {@link GunBuiltinAttachmentCache}。内置附件是
 * 枪型级别数据，帧间不变，缓存安全；命中时仍返回 {@code copy()} 避免共享可变对象。</p>
 */
@Mixin(value = GunItemDataAccessor.class, remap = false)
public interface GunItemDataAccessorMixin {

    @Overwrite
    default ItemStack getBuiltinAttachment(ItemStack gun, AttachmentType type) {
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) {
            return ItemStack.EMPTY;
        }
        ResourceLocation gunId = iGun.getGunId(gun);
        ItemStack cached = GunBuiltinAttachmentCache.get(gunId, type);
        if (cached != null) {
            return cached.copy();
        }
        CommonGunIndex index = TimelessAPI.getCommonGunIndex(gunId).orElse(null);
        ItemStack result = ItemStack.EMPTY;
        if (index != null) {
            Map<AttachmentType, ResourceLocation> builtin = index.getGunData().getBuiltInAttachments();
            if (builtin.containsKey(type)) {
                result = AttachmentItemBuilder.create().setId(builtin.get(type)).build();
            }
        }
        GunBuiltinAttachmentCache.put(gunId, type, result);
        return result.copy();
    }
}
