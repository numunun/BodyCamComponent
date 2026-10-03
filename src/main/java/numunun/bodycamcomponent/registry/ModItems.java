package numunun.bodycamcomponent.registry;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.item.RegionWandItem;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BodyCamComponent.MODID);

    public static final RegistryObject<Item> REGION_WAND = ITEMS.register("region_wand", RegionWandItem::new);

    private ModItems() {}
}