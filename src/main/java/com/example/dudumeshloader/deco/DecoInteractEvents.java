package com.example.dudumeshloader.deco;

import com.example.dudumeshloader.DuDuMeshLoaderMod;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 武器架交互补充：让<b>手持 TACZ 枪/配件</b>右键武器架时，枪能可靠地放入架子。
 *
 * <p>问题背景：TACZ 枪物品会在右键时优先消费交互（开火/上膛），导致方块 {@code use()} 不被调用、
 * 枪永远进不了架子（“拿着武器右键没有任何反应”）。本监听器在 Forge 的
 * {@link PlayerInteractEvent.RightClickBlock} 阶段（早于物品 {@code use()}）拦截：命中武器架且手持
 * 枪/配件时执行放入，成功则取消事件，阻止枪消费这次右键。</p>
 *
 * <p>空手右键不在此处理，仍交由 {@link DecoWeaponRackBlock#use} 走取出/开合逻辑。</p>
 */
@Mod.EventBusSubscriber(modid = DuDuMeshLoaderMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class DecoInteractEvents {
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickRack(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) {
            return;
        }
        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof DecoWeaponRackBlock)) {
            return;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof DecoWeaponRackEntity rack)) {
            return;
        }
        // 双块（DOUBLE_B/c）非根块（HEAD/UPPER）的实体不渲染武器、也不参与取出，
        // 武器只应落在根块（FOOT/LOWER）实体上。若命中非根块直接 insertItem，会出现
        // 「物品消失但不在架子上、空手取不出、只有破坏才掉落」的现象。此处与 use()
        // 一致，先路由到根实体再插入，保证渲染与取出的实体是同一个。
        if (!DecoWeaponRackBlock.isRoot(state)) {
            BlockPos root = DecoWeaponRackBlock.getRootPos(pos, state);
            BlockEntity rootBe = level.getBlockEntity(root);
            if (!(rootBe instanceof DecoWeaponRackEntity rootRack)) {
                return;
            }
            rack = rootRack;
        }
        Player player = event.getEntity();
        ItemStack held = player.getItemInHand(event.getHand());
        if (held.getItem() instanceof IGun || held.getItem() instanceof IAttachment) {
            if (rack.insertItem(held)) {
                event.setCanceled(true);
            }
        }
    }
}
