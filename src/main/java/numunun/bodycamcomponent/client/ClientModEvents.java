package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.registry.ModEntities;
import numunun.bodycamcomponent.util.BodyCamLog;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {

    private ClientModEvents() {}

    // 화면 표시는 모두 safe()로 감싸서, 하나가 오류 나도 게임이 튕기지 않고 그 표시만 건너뜀
    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("ally_markers", safe("ally_markers", AllyMarkers::render));
        e.registerAboveAll("ping_hud", safe("ping_hud", PingHud::render));
        e.registerAboveAll("osd", safe("osd", OsdOverlay::render));
        e.registerAboveAll("bodycam_info", safe("bodycam_info", BodyCamHud::renderInfo));
        e.registerAboveAll("death_effect", safe("death_effect", DeathEffect::render));
        e.registerAboveAll("intro", safe("intro", IntroOverlay::render));
        e.registerAboveAll("team_status", safe("team_status", TeamStatusHud::render));
        e.registerAboveAll("domination_hud", safe("domination_hud", DominationHud::render));
    }

    private static IGuiOverlay safe(String id, IGuiOverlay overlay) {
        return (gui, g, partialTick, w, h) -> {
            try {
                overlay.render(gui, g, partialTick, w, h);
            } catch (Exception ex) {
                BodyCamLog.error("화면 표시: " + id, ex);
            }
        };
    }

    // 쉐이더가 그래픽 카드에서 컴파일되지 않아도 게임은 켜지게 (저체력 흑백 효과만 꺼짐)
    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent e) {
        try {
            e.registerShader(
                    new ShaderInstance(e.getResourceProvider(),
                            new ResourceLocation(BodyCamComponent.MODID, "bodycam_desaturate"),
                            DefaultVertexFormat.POSITION_TEX),
                    shader -> LowHealthEffect.shader = shader);
        } catch (Exception ex) {
            LowHealthEffect.shader = null;
            BodyCamLog.error("흑백 쉐이더 불러오기 (저체력 흑백 효과 꺼짐)", ex);
        }
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(ModEntities.CORPSE.get(), CorpseRenderer::new);
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent e) {
        e.register(PingKey.KEY);
    }
}