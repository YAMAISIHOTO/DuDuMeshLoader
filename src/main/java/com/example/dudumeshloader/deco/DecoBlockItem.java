package com.example.dudumeshloader.deco;

import com.tacz.guns.api.DefaultAssets;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.nbt.BlockItemDataAccessor;
import com.tacz.guns.client.resource.index.ClientBlockIndex;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nonnull;
import java.util.Optional;

/**
 * {@code dudumeshloader:block_deco} 的方块物品。
 *
 * <p>实现 {@link BlockItemDataAccessor}（即 {@code IBlock}），
 * 通过 NBT {@code BlockId} 携带所指向的 TACZ 方块 index。
 * 当手中物品没有显式 {@code BlockId} 时，回退到默认的 {@link DecoRegistries#DECO_BLOCK_ID}，
 * 使得直接从创造标签页拿到的物品也能正确渲染。</p>
 *
 * <p>名称解析与 TACZ 的 {@code GunSmithTableItem} 完全一致：方块 index JSON 的
 * {@code name} 字段被视为<b>翻译键</b>，通过 {@link Component#translatable(String)} 解析，
 * 因此枪包作者可在 lang 文件中对 {@code name} 给出的键做汉化。
 * 若 index 未加载（例如服务端），则回退到父类的固定键 {@code block.dudumeshloader.block_deco}。</p>
 */
public class DecoBlockItem extends BlockItem implements BlockItemDataAccessor {
    public DecoBlockItem(net.minecraft.world.level.block.Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    @Nonnull
    @OnlyIn(Dist.CLIENT)
    public Component getName(@Nonnull ItemStack stack) {
        // getBlockId 已把空 BlockId 归一化为 DECO_BLOCK_ID，故此处总是有效键。
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
        return id.equals(DefaultAssets.EMPTY_BLOCK_ID) ? DecoRegistries.DECO_BLOCK_ID : id;
    }
}
