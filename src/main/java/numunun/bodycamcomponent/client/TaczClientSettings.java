package numunun.bodycamcomponent.client;

import com.mojang.logging.LogUtils;
import com.tacz.guns.client.renderer.crosshair.CrosshairType;
import com.tacz.guns.config.client.RenderConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import org.slf4j.Logger;

// TaCZ 클라이언트 설정을 바디캠에 맞게 고정
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, value = Dist.CLIENT)
public final class TaczClientSettings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean failed = false;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || failed) return;
        try {
            // 크로스헤어 숨김
            if (RenderConfig.CROSSHAIR_TYPE.get() != CrosshairType.EMPTY) {
                RenderConfig.CROSSHAIR_TYPE.set(CrosshairType.EMPTY);
            }
            // 탄약 HUD 숨김
            if (RenderConfig.GUN_HUD_ENABLE.get()) {
                RenderConfig.GUN_HUD_ENABLE.set(false);
            }
            // 킬 카운트 숨김
            if (RenderConfig.KILL_AMOUNT_ENABLE.get()) {
                RenderConfig.KILL_AMOUNT_ENABLE.set(false);
            }
        } catch (IllegalStateException ex) {
            // 설정 파일이 아직 로드되기 전이면 다음 틱에 재시도
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("[BodyCam] Failed to apply TaCZ client settings", t);
        }
    }
}