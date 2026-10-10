package numunun.bodycamcomponent.registry;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.entity.CorpseEntity;

public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, BodyCamComponent.MODID);

    public static final RegistryObject<EntityType<CorpseEntity>> CORPSE = ENTITIES.register("corpse",
            () -> EntityType.Builder.<CorpseEntity>of(CorpseEntity::new, MobCategory.MISC)
                    .sized(0.8f, 0.3f)
                    .clientTrackingRange(8)
                    .updateInterval(5)
                    .build("corpse"));

    private ModEntities() {}
}