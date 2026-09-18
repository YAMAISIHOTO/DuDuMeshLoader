package com.example.dudumeshloader.deco;

import com.example.dudumeshloader.DuDuMeshLoaderMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * {@code dudumeshloader:block_deco} 的注册中心。
 *
 * <p>方块、物品、方块实体类型三者共用同一个注册名 {@code block_deco}，
 * 并统一归属 {@code dudumeshloader} 命名空间。</p>
 */
public class DecoRegistries {
    public static final ResourceLocation DECO_BLOCK_ID = new ResourceLocation(DuDuMeshLoaderMod.MOD_ID, "block_deco");

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, DuDuMeshLoaderMod.MOD_ID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, DuDuMeshLoaderMod.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> TILE_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, DuDuMeshLoaderMod.MOD_ID);

    public static final RegistryObject<DecoBlock> BLOCK = BLOCKS.register("block_deco", DecoBlock::new);
    public static final RegistryObject<Item> ITEM = ITEMS.register("block_deco", () -> new DecoBlockItem(BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<DecoBlockEntity>> BLOCK_ENTITY = TILE_ENTITIES.register("block_deco",
            () -> BlockEntityType.Builder.of(DecoBlockEntity::new, BLOCK.get()).build(null));

    public static final ResourceLocation RACK_BLOCK_ID = new ResourceLocation(DuDuMeshLoaderMod.MOD_ID, "weapon_rack");
    public static final RegistryObject<DecoWeaponRackBlock> RACK_BLOCK = BLOCKS.register("weapon_rack", DecoWeaponRackBlock::new);
    public static final RegistryObject<Item> RACK_ITEM = ITEMS.register("weapon_rack", () -> new DecoWeaponRackItem(RACK_BLOCK.get(), new Item.Properties()));
    public static final RegistryObject<BlockEntityType<DecoWeaponRackEntity>> RACK_BLOCK_ENTITY = TILE_ENTITIES.register("weapon_rack",
            () -> BlockEntityType.Builder.of(DecoWeaponRackEntity::new, RACK_BLOCK.get()).build(null));

    public static void init(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TILE_ENTITIES.register(bus);
    }
}
