package numunun.bodycamcomponent.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;

import java.io.IOException;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    @SubscribeEvent
    public static void registerOverlays(RegisterGuiOverlaysEvent e) {
        e.registerAboveAll("bodycam_info", BodyCamHud::renderInfo);
    }

    @SubscribeEvent
    public static void registerShaders(RegisterShadersEvent e) throws IOException {
        e.registerShader(
                new ShaderInstance(e.getResourceProvider(),
                        new ResourceLocation(BodyCamComponent.MODID, "bodycam_desaturate"),
                        DefaultVertexFormat.POSITION_TEX),
                shader -> LowHealthEffect.shader = shader);
    }
}