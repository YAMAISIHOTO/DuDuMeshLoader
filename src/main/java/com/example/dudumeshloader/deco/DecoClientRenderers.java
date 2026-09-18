package com.example.dudumeshloader.deco;

import com.example.dudumeshloader.DuDuMeshLoaderMod;
import com.example.dudumeshloader.tacz.AttachmentPolyCache;
import com.example.dudumeshloader.tacz.GunBuiltinAttachmentCache;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = DuDuMeshLoaderMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class DecoClientRenderers {
    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(DecoRegistries.BLOCK_ENTITY.get(), DecoBlockRenderer::new);
        event.registerBlockEntityRenderer(DecoRegistries.RACK_BLOCK_ENTITY.get(), DecoWeaponRackRenderer::new);
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void object, ResourceManager resourceManager, ProfilerFiller profiler) {
                DecoWeaponRackRenderer.onResourceReload();
                // 资源重载后丢弃已构建的 poly_mesh 缓存，避免复用失效的 geo 解析结果
                // （下次索引重建时按 geoPath 重新解析，整个会话仍只解析一次）。
                AttachmentPolyCache.clear();
                // 内置附件定义随枪包资源重载可能变化，同样需要失效。
                GunBuiltinAttachmentCache.clear();
            }
        });
    }

    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        // 注视武器架且手持枪/配件时显示「按 O 交互」提示，挂在准星之上。
        event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "dudumeshloader_rack_overlay", new DecoWeaponRackOverlay());
    }
}
