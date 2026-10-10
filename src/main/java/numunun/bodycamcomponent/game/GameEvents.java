package numunun.bodycamcomponent.game;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.server.ServerLifecycleHooks;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.entity.CorpseEntity;
import numunun.bodycamcomponent.registry.ModItems;
import numunun.bodycamcomponent.util.BodyCamLog;
import numunun.bodycamcomponent.util.Safe;

// 서버 이벤트 → 경기 시스템 연결. 모든 처리는 Safe로 감싸서 오류가 나도 서버가 멈추지 않음
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID)
public final class GameEvents {
    private static final int MAX_TICK_ERRORS = 100;  // 연속 오류가 이만큼 쌓이면 경기를 안전하게 중단
    private static int tickErrors = 0;

    private GameEvents() {}

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
        if (!(e.getEntity() instanceof ServerPlayer victim)
                || !(e.getSource().getEntity() instanceof ServerPlayer attacker)
                || attacker == victim) return;

        Safe.run("피해 기록", () -> {
            GameManager.recordAttack(victim, attacker);
            double mult = BodyCamConfig.LEADER_DAMAGE_MULTIPLIER.get();
            if (mult != 1.0 && GameManager.isLeader(attacker)) {
                e.setAmount((float) (e.getAmount() * mult));
            }
        });
    }

    // LOWEST: 다른 모드가 사망을 취소한 경우엔 실행되지 않음 (빈사는 그 전 단계에서 처리됨)
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        Safe.run("사망 처리", () -> GameManager.onDeath(p, e.getSource()));
        if (BodyCamConfig.CORPSE_ENABLED.get()) Safe.run("시체 생성", () -> CorpseEntity.spawnFor(p));
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        if (!e.isEndConquered() && e.getEntity() instanceof ServerPlayer p) {
            Safe.run("리스폰 처리", () -> GameManager.onRespawn(p));
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;

        try {
            GameManager.tick(server);
            tickErrors = 0;
        } catch (Exception ex) {
            BodyCamLog.error("경기 진행", ex);
            if (++tickErrors >= MAX_TICK_ERRORS) {
                tickErrors = 0;
                GameManager.abortOnError(server);
            }
        }
        Safe.run("정찰 지원 적 식별", () -> PingManager.tickSpotting(server));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Safe.run("접속 처리", () -> GameManager.onLogin(p));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) Safe.run("퇴장 처리", () -> GameManager.onLogout(p));
    }

    // 일시정지 중 플레이어 피해 차단
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent e) {
        if (GameManager.isPaused() && e.getEntity() instanceof ServerPlayer) e.setCanceled(true);
    }

    // 개발 환경 전용: LAN으로 두 번째 클라이언트가 접속할 수 있게 정품 인증 끄기
    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent e) {
        if (!FMLEnvironment.production) {
            e.getServer().setUsesAuthentication(false);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent e) {
        Safe.run("서버 종료 시 경기 정리", () -> GameManager.shutdown(e.getServer()));
    }
}