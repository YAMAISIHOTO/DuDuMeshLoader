package com.example.dudumeshloader.deco;

import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.nbt.BlockItemDataAccessor;
import com.tacz.guns.client.resource.index.ClientBlockIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * {@code dudumeshloader:weapon_rack} 的方块物品。
 *
 * <p>实现 {@link BlockItemDataAccessor}（即 {@code IBlock}），通过 NBT {@code BlockId}
 * 携带所指向的 TACZ 方块 index。空 NBT 时回退到 {@link DecoRegistries#RACK_BLOCK_ID}。
 * 名称解析与 TACZ 的 {@code GunSmithTableItem} 一致：index JSON 的 {@code name} 字段
 * 作为翻译键，由附属包 lang 提供汉化。</p>
 */
public class DecoWeaponRackItem extends BlockItem implements BlockItemDataAccessor {
    public DecoWeaponRackItem(net.minecraft.world.level.block.Block block, net.minecraft.world.item.Item.Properties properties) {
        super(block, properties);
    }

    @Override
    @Nonnull
    @OnlyIn(Dist.CLIENT)
    public Component getName(@Nonnull ItemStack stack) {
        ResourceLocation blockId = this.getBlockId(stack);
        Optional<ClientBlockIndex> blockIndex = TimelessAPI.getClientBlockIndex(blockId);
        if (blockIndex.isPresent()) {
            return Component.translatable(blockIndex.get().getName());
        }
        return super.getName(stack);
    }

    @Override
    public ResourceLocation getBlockId(ItemStack stack) {
        ResourceLocation id = BlockItemDataAccessor.super.getBlockId(stack);
        return id.equals(DefaultAssets.EMPTY_BLOCK_ID) ? DecoRegistries.RACK_BLOCK_ID : id;
    }
}
