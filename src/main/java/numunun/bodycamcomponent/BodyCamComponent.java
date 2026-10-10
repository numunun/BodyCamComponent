package numunun.bodycamcomponent;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.registry.ModItems;
import numunun.bodycamcomponent.registry.ModEntities;
import numunun.bodycamcomponent.network.BodyCamNetwork;

@Mod(BodyCamComponent.MODID)
public class BodyCamComponent {
    public static final String MODID = "bodycamcomponent";

    public BodyCamComponent() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modBus);
        ModEntities.ENTITIES.register(modBus);   // 추가
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, BodyCamConfig.SPEC);
        BodyCamNetwork.register();
    }
}