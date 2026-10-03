package numunun.bodycamcomponent.client;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class BodyCamCamera {
    private static final boolean ALLOW_THIRD_PERSON_IN_CREATIVE = false;

    // ── 3인칭 차단 ──
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (ALLOW_THIRD_PERSON_IN_CREATIVE && (p.isCreative() || p.isSpectator())) return;

        if (mc.options.getCameraType() != CameraType.FIRST_PERSON) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            mc.gui.setOverlayMessage(Component.literal("바디캠 시점은 변경할 수 없습니다"), false);
        }
    }
}