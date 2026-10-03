package numunun.bodycamcomponent.game;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.registry.ModItems;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID)
public final class GameEvents {

    // 구역 지정 도구 좌클릭: 지점 1
    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock e) {
        if (!e.getItemStack().is(ModItems.REGION_WAND.get())) return;
        e.setCanceled(true);
        if (e.getEntity() instanceof ServerPlayer p) {
            Selections.setPos(p, 0, e.getPos());
        }
    }

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent e) {
        if (e.getEntity() instanceof ServerPlayer victim
                && e.getSource().getEntity() instanceof ServerPlayer attacker
                && attacker != victim) {
            GameManager.recordAttack(victim, attacker);
        }
    }

    // LOWEST: PlayerRevive가 빈사로 전환(사망 취소)한 경우엔 실행되지 않음
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            GameManager.onDeath(p, e.getSource());
        }
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!e.isEndConquered() && e.getEntity() instanceof ServerPlayer p) {
            GameManager.onRespawn(p);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent e) {
        GameManager.reset();
    }
}