package com.example.dudumeshloader.deco;

import com.example.dudumeshloader.DuDuMeshLoaderMod;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.input.InteractKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * 武器架的按 O 放入交互。
 *
 * <p>TACZ 的 {@code ClientPreventGunClick} 会在手持枪时拦截右键（除非 {@code InteractKey} 按下），
 * 持枪右键到不了方块。这里直接监听 TACZ 的 {@link InteractKey#INTERACT_KEY}（默认 O，尊重玩家改键）：
 * 命中武器架且手持枪或配件时调用 {@code Minecraft.gameMode.useItemOn(...)}，相当于一次定向右键，
 * 由服务端 {@code DecoInteractEvents} 完成放入，故只需按 O。空手时不处理（开合仍走潜行 + 右键）。</p>
 *
 * <p>不能用反射调 {@code Minecraft.startUseItem()}：生产环境方法名是 SRG 名，
 * {@code getDeclaredMethod} 必抛 {@code NoSuchMethodException} 且被静默吞掉。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = DuDuMeshLoaderMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public class DecoWeaponRackInteract {
    /** 去抖：防止与 TACZ 自身 O 键监听（若武器架被纳入 interact_key 白名单）同帧重复触发。 */
    private static long lastTriggerMs = 0;

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (!InteractKey.INTERACT_KEY.matches(event.getKey(), event.getScanCode())) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator()) {
            return;
        }
        HitResult hit = mc.hitResult;
        if (!(hit instanceof BlockHitResult bhr)) {
            return;
        }
        BlockPos pos = bhr.getBlockPos();
        BlockState state = player.level().getBlockState(pos);
        if (!(state.getBlock() instanceof DecoWeaponRackBlock)) {
            return;
        }
        // 确定手持枪/配件的手（主手优先，否则副手）
        ItemStack held = player.getMainHandItem();
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (!(held.getItem() instanceof IGun || held.getItem() instanceof IAttachment)) {
            held = player.getOffhandItem();
            hand = InteractionHand.OFF_HAND;
            if (!(held.getItem() instanceof IGun || held.getItem() instanceof IAttachment)) {
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastTriggerMs < 250) {
            return;
        }
        lastTriggerMs = now;
        // 直接触发方块 use()（服务端执行插入）。等价于右键，但绕过 TACZ 持枪时对右键的拦截。
        // 使用公开稳定的 gameMode.useItemOn，避免反射 private startUseItem 在生产环境因 SRG 混淆名失效。
        try {
            mc.gameMode.useItemOn(player, hand, bhr);
        } catch (Exception ignored) {
            // 极少发生（方法为公开 API），失败则静默跳过
        }
    }
}
