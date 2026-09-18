package com.example.dudumeshloader.deco;

import com.example.dudumeshloader.DuDuMeshLoaderMod;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 将 {@code dudumeshloader:block_deco} 物品加入原版「功能性方块」创造标签页，
 * 方便在游戏内直接获取用于测试。
 */
@Mod.EventBusSubscriber(modid = DuDuMeshLoaderMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class DecoModEvents {
    @SubscribeEvent
    public static void onCreativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS.location())) {
            event.accept(DecoRegistries.ITEM.get());
            event.accept(DecoRegistries.RACK_ITEM.get());
        }
    }
}
