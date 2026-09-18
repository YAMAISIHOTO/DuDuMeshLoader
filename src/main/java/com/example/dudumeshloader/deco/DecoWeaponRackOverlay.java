package com.example.dudumeshloader.deco;

import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.client.input.InteractKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * 注视武器架且手持枪/配件时，在屏幕准星下方显示「按 O 交互」提示（仿 TACZ statue 的交互文案）。
 *
 * <p>仅做提示，不拦截任何输入；实际放入由 {@link DecoWeaponRackInteract} 在按下 TACZ 交互键时触发。</p>
 */
public class DecoWeaponRackOverlay implements IGuiOverlay {
    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick, int width, int height) {
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
        ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof IGun || held.getItem() instanceof IAttachment)) {
            held = player.getOffhandItem();
            if (!(held.getItem() instanceof IGun || held.getItem() instanceof IAttachment)) {
                return;
            }
        }
        String keyName = InteractKey.INTERACT_KEY.getTranslatedKeyMessage().getString();
        Component title = Component.literal("按 " + keyName + " 交互");
        Font font = mc.font;
        graphics.drawString(font, title,
                (int) ((width - font.width(title)) / 2.0F),
                (int) (height / 2.0F - 25),
                0xFFFF55, false);
    }
}
