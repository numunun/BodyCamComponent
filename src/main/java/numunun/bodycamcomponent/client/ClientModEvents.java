package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import numunun.bodycamcomponent.registry.ModEntities;
import numunun.bodycamcomponent.BodyCamComponent;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("ally_markers", AllyMarkers::render);   // 추가
        e.registerAboveAll("ping_hud", PingHud::render);
        e.registerAboveAll("osd", OsdOverlay::render);
        e.registerAboveAll("bodycam_info", BodyCamHud::renderInfo);
        e.registerAboveAll("death_effect", DeathEffect::render);
        e.registerAboveAll("intro", IntroOverlay::render);
        e.registerAboveAll("team_status", TeamStatusHud::render);
        e.registerAboveAll("domination_hud", DominationHud::render);
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent e) throws IOException {
        e.registerShader(
                new ShaderInstance(e.getResourceProvider(),
                        new ResourceLocation(BodyCamComponent.MODID, "bodycam_desaturate"),
                        DefaultVertexFormat.POSITION_TEX),
                shader -> LowHealthEffect.shader = shader);
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