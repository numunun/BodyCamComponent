package numunun.bodycamcomponent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.network.AbilityClientData;

// 경기 중에는 채팅창을 화면에 띄우지 않음 (T로 열면 그동안 온 메시지가 보임)
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class MatchChatHider {

    private MatchChatHider() {}

    @SubscribeEvent
    public static void onRenderOverlay(RenderGuiOverlayEvent.Pre e) {
        if (!e.getOverlay().id().equals(VanillaGuiOverlay.CHAT_PANEL.id())) return;
        if (!BodyCamConfig.HIDE_CHAT_IN_MATCH.get()) return;
        if (AbilityClientData.role.isEmpty()) return;                        // 경기 중이 아니면 그대로
        if (Minecraft.getInstance().screen instanceof ChatScreen) return;    // 채팅을 직접 열면 보임
        e.setCanceled(true);
    }
}