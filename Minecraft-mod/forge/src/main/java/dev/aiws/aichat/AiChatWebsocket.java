package dev.aiws.aichat;

import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * AI Chat WebSocket —— 纯客户端模组（Forge 1.20.1 / 47.x，Java 17）。
 *
 * 把游戏聊天经 WebSocket 转发给 AI 服务，AI 回复以玩家身份发回聊天栏；
 * 对 TrChat 类聊天插件默认自动兼容。功能与 Fabric 端 1.0.0-r3 完全一致。
 *
 * Forge 1.20.1 没有"仅客户端"元数据标记，客户端逻辑全部包在
 * FMLEnvironment.dist == Dist.CLIENT 判断内，由代码保证。
 */
@Mod(AiChatWebsocket.MOD_ID)
public final class AiChatWebsocket {

    public static final String MOD_ID = "ai_chat_websocket";
    public static final String MOD_VERSION = "1.0.0-r3";
    public static final String MC_VERSION = "1.20.1";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AiChatWebsocket() {
        Path configFile = FMLPaths.CONFIGDIR.get().resolve(AiConfig.FILE_NAME);
        AiConfig config = AiConfig.load(configFile);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientBridge bridge = ClientBridge.create(config, configFile);
            MinecraftForge.EVENT_BUS.register(bridge);
        }
    }
}
