package numunun.bodycamcomponent;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import numunun.bodycamcomponent.registry.ModItems;

@Mod(BodyCamComponent.MODID)
public class BodyCamComponent {
    public static final String MODID = "bodycamcomponent";

    public BodyCamComponent() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModItems.ITEMS.register(modBus);
    }
}