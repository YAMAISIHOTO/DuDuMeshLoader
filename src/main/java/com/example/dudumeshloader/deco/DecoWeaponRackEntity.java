package com.example.dudumeshloader.deco;

import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.List;

import static com.example.dudumeshloader.deco.DecoWeaponRackBlock.FACING;

/**
 * 武器架方块实体。
 *
 * <p>持久化三样东西：{@code BlockId}（指向武器架自身的 TACZ 方块 index，决定模型与槽位定义）、
 * {@code Weapons}（各槽位放置的 TACZ 枪，落在 {@code weapon_mount} 系骨骼）与
 * {@code Attachments}（各槽位放置的 TACZ 配件，落在 {@code attachment_mount} 系骨骼）。</p>
 *
 * <p>槽位数分别由 index 的 {@code weapon_mounts}（武器，可带类型约束）/ {@code attachment_bones}（配件）
 * 列表长度决定，实体各按对应长度维护一个 {@link ItemStack} 列表。武器与配件分两套独立槽位，互不占用。
 * 放入武器时按槽位 {@code type} 与手持枪类型匹配，无匹配空槽则拒绝放入。</p>
 */
public class DecoWeaponRackEntity extends BlockEntity {
    private static final String ID_TAG = "BlockId";
    private static final String WEAPONS_TAG = "Weapons";
    private static final String ATTACHMENTS_TAG = "Attachments";
    private static final String ITEMS_TAG = "Items"; // 旧版存档兼容
    private static final String OPEN_TAG = "Open";

    @Nullable
    private ResourceLocation id = null;
    private List<ItemStack> weaponItems = new ArrayList<>();
    private List<ItemStack> attachmentItems = new ArrayList<>();
    private boolean open = false;

    public DecoWeaponRackEntity(BlockPos pos, BlockState state) {
        super(DecoRegistries.RACK_BLOCK_ENTITY.get(), pos, state);
    }

    public void setId(ResourceLocation id) {
        this.id = id;
        ensureSize();
    }

    @Nullable
    public ResourceLocation getId() {
        return id;
    }

    /** 武器架开合状态：true = 开启（播放 idle_open），false = 关闭（播放 idle_close）。 */
    public boolean isOpen() {
        return open;
    }

    /** 切换开合状态；仅在值变化时标记更新以触发客户端同步。 */
    public void setOpen(boolean open) {
        if (this.open != open) {
            this.open = open;
            org.apache.logging.log4j.LogManager.getLogger(DecoWeaponRackEntity.class)
                    .info("[DecoRack] setOpen -> {} (serverSide={})", open, !level.isClientSide());
            markUpdated();
        }
    }

    public List<ItemStack> getWeaponItems() {
        return weaponItems;
    }

    public List<ItemStack> getAttachmentItems() {
        return attachmentItems;
    }

    public ItemStack getWeaponItem(int slot) {
        return (slot >= 0 && slot < weaponItems.size()) ? weaponItems.get(slot) : ItemStack.EMPTY;
    }

    public ItemStack getAttachmentItem(int slot) {
        return (slot >= 0 && slot < attachmentItems.size()) ? attachmentItems.get(slot) : ItemStack.EMPTY;
    }

    /**
     * 放入：按物品类型分流——{@code IGun} 进武器槽位（按槽位 type 约束匹配），
     * {@code IAttachment} 进配件槽位（attachment_mount 系）。成功占用并消耗 1 个手持物返回 true。
     */
    public boolean insertItem(ItemStack stack) {
        ensureSize();
        if (stack.getItem() instanceof IGun) {
            return insertGun(weaponItems, stack, getGunType(stack));
        }
        if (stack.getItem() instanceof IAttachment) {
            return insertInto(attachmentItems, stack);
        }
        return false;
    }

    /**
     * 放入枪：取手持枪的类型，按索引顺序找到第一个「类型匹配 且 该槽为空」的武器槽放入。
     * 类型来自 {@code CommonGunIndex.getType}（服务端安全）。无任何匹配空槽则返回 false（枪留在手里）。
     */
    private boolean insertGun(List<ItemStack> list, ItemStack stack, String gunType) {
        List<DecoMountBoneRegistry.WeaponMountSlot> slots = (id != null)
                ? DecoMountBoneRegistry.getWeaponSlots(id)
                : List.of(new DecoMountBoneRegistry.WeaponMountSlot(DecoMountBoneRegistry.WEAPON_DEFAULT, List.of()));
        int n = Math.min(list.size(), slots.size());
        for (int i = 0; i < n; i++) {
            if (list.get(i).isEmpty() && slots.get(i).accepts(gunType)) {
                list.set(i, stack.copy());
                stack.shrink(1);
                markUpdated();
                return true;
            }
        }
        return false;
    }

    /** 从 ItemStack 取 TaCZ 枪类型字符串（pistol/rifle/sniper/smg…），取不到返回空串。 */
    private static String getGunType(ItemStack stack) {
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun == null) {
            return "";
        }
        ResourceLocation gunId = gun.getGunId(stack);
        if (gunId == null) {
            return "";
        }
        return com.tacz.guns.api.TimelessAPI.getCommonGunIndex(gunId)
                .map(com.tacz.guns.resource.index.CommonGunIndex::getType)
                .orElse("");
    }

    private boolean insertInto(List<ItemStack> list, ItemStack stack) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isEmpty()) {
                list.set(i, stack.copy());
                stack.shrink(1);
                markUpdated();
                return true;
            }
        }
        return false; // 该类别槽位已满
    }

    /** 取第一把非空：先武器槽位（按 mount_bones 顺序），后配件槽位（按 attachment_bones 顺序）。 */
    @Nonnull
    public ItemStack extractFirst() {
        ensureSize();
        ItemStack taken = firstNonEmpty(weaponItems);
        if (!taken.isEmpty()) {
            return taken;
        }
        return firstNonEmpty(attachmentItems);
    }

    private ItemStack firstNonEmpty(List<ItemStack> list) {
        for (int i = 0; i < list.size(); i++) {
            if (!list.get(i).isEmpty()) {
                ItemStack taken = list.get(i);
                list.set(i, ItemStack.EMPTY);
                markUpdated();
                return taken;
            }
        }
        return ItemStack.EMPTY;
    }

    /** 掉落所有武器与配件（破坏方块时调用）。 */
    public void dropAll() {
        if (level == null) {
            return;
        }
        Direction facing = getBlockState().getValue(FACING);
        for (ItemStack s : weaponItems) {
            if (!s.isEmpty()) {
                Block.popResource(level, worldPosition.relative(facing).above(), s);
            }
        }
        for (ItemStack s : attachmentItems) {
            if (!s.isEmpty()) {
                Block.popResource(level, worldPosition.relative(facing).above(), s);
            }
        }
        weaponItems.replaceAll(s -> ItemStack.EMPTY);
        attachmentItems.replaceAll(s -> ItemStack.EMPTY);
        markUpdated();
    }

    private void ensureSize() {
        if (id == null) {
            ensure(weaponItems, 1);
            ensure(attachmentItems, 1);
            return;
        }
        ensure(weaponItems, DecoMountBoneRegistry.getWeaponSlots(id).size());
        ensure(attachmentItems, DecoMountBoneRegistry.getAttachmentBones(id).size());
    }

    private static void ensure(List<ItemStack> list, int n) {
        while (list.size() < Math.max(1, n)) {
            list.add(ItemStack.EMPTY);
        }
    }

    private void markUpdated() {
        if (level != null) {
            BlockState state = level.getBlockState(worldPosition);
            level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_ALL);
        }
        this.setChanged();
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains(ID_TAG, Tag.TAG_STRING)) {
            this.id = ResourceLocation.tryParse(tag.getString(ID_TAG));
        } else {
            this.id = null;
        }
        this.open = tag.getBoolean(OPEN_TAG);
        weaponItems = new ArrayList<>();
        attachmentItems = new ArrayList<>();
        if (tag.contains(WEAPONS_TAG, Tag.TAG_LIST)) {
            ListTag list = tag.getList(WEAPONS_TAG, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                weaponItems.add(ItemStack.of(list.getCompound(i)));
            }
        }
        if (tag.contains(ATTACHMENTS_TAG, Tag.TAG_LIST)) {
            ListTag list = tag.getList(ATTACHMENTS_TAG, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                attachmentItems.add(ItemStack.of(list.getCompound(i)));
            }
        } else if (tag.contains(ITEMS_TAG, Tag.TAG_LIST)) {
            // 向后兼容：旧存档只有 Items（全部当作武器处理）
            ListTag list = tag.getList(ITEMS_TAG, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                weaponItems.add(ItemStack.of(list.getCompound(i)));
            }
        }
        ensureSize();
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (id != null) {
            tag.putString(ID_TAG, id.toString());
        }
        tag.putBoolean(OPEN_TAG, open);
        ListTag wlist = new ListTag();
        for (ItemStack s : weaponItems) {
            wlist.add(s.save(new CompoundTag()));
        }
        tag.put(WEAPONS_TAG, wlist);
        ListTag alist = new ListTag();
        for (ItemStack s : attachmentItems) {
            alist.add(s.save(new CompoundTag()));
        }
        tag.put(ATTACHMENTS_TAG, alist);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    public AABB getRenderBoundingBox() {
        return new AABB(worldPosition.offset(-2, 0, -2), worldPosition.offset(2, 2, 2));
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
