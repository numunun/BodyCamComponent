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
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.server.ServerLifecycleHooks;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.registry.ModItems;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.entity.CorpseEntity;

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

            double mult = BodyCamConfig.LEADER_DAMAGE_MULTIPLIER.get();
            if (mult != 1.0 && GameManager.isLeader(attacker)) {
                e.setAmount((float) (e.getAmount() * mult));
            }
        }
    }

    // LOWEST: PlayerRevive가 빈사로 전환(사망 취소)한 경우엔 실행되지 않음
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            GameManager.onDeath(p, e.getSource());
            if (BodyCamConfig.CORPSE_ENABLED.get()) CorpseEntity.spawnFor(p);
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
        GameManager.reset(e.getServer());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            GameManager.tick(server);
            PingManager.tickSpotting(server);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) GameManager.onLogout(p);
    }

    // 개발 환경 전용: LAN으로 두 번째 클라이언트가 접속할 수 있게 정품 인증 끄기
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent e) {
        if (!FMLEnvironment.production) {
            e.getServer().setUsesAuthentication(false);
        }
    }

    // 일시정지 중 플레이어 피해 차단
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent e) {
        if (GameManager.isPaused() && e.getEntity() instanceof ServerPlayer) e.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) GameManager.onLogin(p);
    }
}