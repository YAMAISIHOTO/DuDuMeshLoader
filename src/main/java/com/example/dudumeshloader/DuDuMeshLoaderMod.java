package com.example.dudumeshloader;

import com.example.dudumeshloader.config.ClientConfig;
import com.example.dudumeshloader.deco.DecoRegistries;
import com.example.dudumeshloader.render.MeshyBatchFlushHandler;
import com.example.dudumeshloader.render.ShaderStateTracker;
import com.example.dudumeshloader.tacz.TaczMeshyIntegration;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(DuDuMeshLoaderMod.MOD_ID)
public class DuDuMeshLoaderMod {

    public static final String MOD_ID = "dudumeshloader";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DuDuMeshLoaderMod() {
        LOGGER.info("[DuDuMeshLoader] Initialized.");

        // 注册装饰方块 block_deco（方块 / 物品 / 方块实体类型）
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        DecoRegistries.init(modBus);
        LOGGER.info("[DuDuMeshLoader] Registered deco block registries.");

        if (FMLEnvironment.dist == Dist.CLIENT) {
            // 圆滑法线在模型创建时取配置快照；配置文件为 dudumeshloader-client.toml。
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
            LOGGER.info("[DuDuMeshLoader] Registered client config.");

            FMLJavaModLoadingContext.get().getModEventBus()
                    .addListener(TaczMeshyIntegration::onClientSetup);
            LOGGER.info("[DuDuMeshLoader] Registered TacZ client setup listener.");

            // フレームレベルの translucent バッチフラッシュハンドラを登録
            // これにより地面に複数の銃を落としても translucent は 1 フレームに 1 回だけフラッシュされる
            MinecraftForge.EVENT_BUS.register(MeshyBatchFlushHandler.class);
            LOGGER.info("[DuDuMeshLoader] Registered batch flush handler.");

            // Oculus シェーダー切り替え時に VBO キャッシュを無効化するトラッカーを登録
            // これにより、シェーダーを切り替えた際にメッシュモデルの影が反転する問題を修正する
            MinecraftForge.EVENT_BUS.register(ShaderStateTracker.class);
            LOGGER.info("[DuDuMeshLoader] Registered shader state tracker.");
        }
    }
}
