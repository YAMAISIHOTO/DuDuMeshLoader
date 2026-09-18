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
 * 武器架的「按 O 放入」交互。
 *
 * <p>背景：TACZ 的 {@code ClientPreventGunClick} 会在<b>手持枪</b>时拦截右键（除非 {@code InteractKey} 按下），
 * 导致持枪右键根本到不了方块（必须先按住 O 进入交互模式，右键才放行）。因此原先「放武器」需要 O + 右键同时按。</p>
 *
 * <p>本监听器直接监听 TACZ 的 {@link InteractKey#INTERACT_KEY}（默认 O，尊重玩家改键）：命中武器架且
 * 手持 TACZ 枪或配件时，直接调用 {@code Minecraft.gameMode.useItemOn(...)} 触发对当前注视方块的 use()，
 * 由服务端 {@code DecoInteractEvents} 执行放入。相当于一次「定向右键」，但绕过了持枪右键被拦截的问题，
 * 故<b>只需按 O 即可放入</b>。</p>
 *
 * <p>注意：早期版本此处用反射调用 {@code Minecraft.startUseItem()}，但生产环境 jar 运行时方法名为 SRG/混淆名，
 * 反射 {@code getDeclaredMethod("startUseItem")} 必然抛 {@code NoSuchMethodException} 且被静默吞掉，导致 O 单独
 * 无效。改用公开稳定的 {@code gameMode.useItemOn(player, hit, hand)} 彻底消除该脆弱性。</p>
 *
 * <p>空手时本监听器不处理（开合仍走原先的 潜行+右键；TACZ 的 O 键在空手时本就无作为）。</p>
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
