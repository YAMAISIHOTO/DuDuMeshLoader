package com.example.dudumeshloader.deco;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/**
 * {@code dudumeshloader:block_deco} 的方块实体。
 *
 * <p>仅负责持久化 {@code BlockId}（指向 TACZ 方块 index，从而决定模型与碰撞档位），
 * 并提供方块实体渲染所需的包围盒。不实现 {@code MenuProvider}（无 GUI）。</p>
 */
public class DecoBlockEntity extends BlockEntity {
    private static final String ID_TAG = "BlockId";

    @Nullable
    private ResourceLocation id = null;

    public DecoBlockEntity(BlockPos pos, BlockState state) {
        super(DecoRegistries.BLOCK_ENTITY.get(), pos, state);
    }

    public void setId(ResourceLocation id) {
        this.id = id;
    }

    @Nullable
    public ResourceLocation getId() {
        return id;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition.offset(-2, 0, -2), worldPosition.offset(2, 1, 2));
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(ID_TAG, Tag.TAG_STRING)) {
            this.id = ResourceLocation.tryParse(tag.getString(ID_TAG));
        } else {
            this.id = null;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (id != null) {
            tag.putString(ID_TAG, id.toString());
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
}
