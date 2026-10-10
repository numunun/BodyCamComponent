package numunun.bodycamcomponent.downed;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.game.GameManager;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.DownedSyncPacket;
import numunun.bodycamcomponent.util.BodyCamLog;
import numunun.bodycamcomponent.util.MatchLog;
import numunun.bodycamcomponent.util.Safe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 빈사 시스템: 치명상 → 빈사 → 팀원 치료 또는 출혈사
@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID)
public final class DownedManager {
    private static final int INVULNERABLE_TICKS = 20;  // 쓰러진 직후 1초 무적
    private static final int GIVE_UP_TICKS = 60;        // Shift 3초 유지 시 포기
    private static final double REVIVE_RANGE_SQ = 2.0 * 2.0;
    private static final double HINT_RANGE_SQ = 6.0 * 6.0;

    private static final class State {
        int ticksLeft;
        int downedTicks;
        int giveUpTicks;
        int reviveProgress;
    }

    private static final Map<UUID, State> states = new HashMap<>();
    private static volatile Set<UUID> clientDowned = Set.of();

    private DownedManager() {}

    // 서버/클라이언트 양쪽에서 사용 가능
    public static boolean isDowned(Player p) {
        return p.level().isClientSide ? clientDowned.contains(p.getUUID()) : states.containsKey(p.getUUID());
    }

    public static boolean isClientDowned(UUID id) {
        return clientDowned.contains(id);
    }

    public static void setClientDowned(Collection<UUID> ids) {
        clientDowned = Set.copyOf(ids);
    }

    // ── 치명상 → 빈사 (죽기 전 단계에서 가로챔) ──
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDamage(LivingDamageEvent e) {
        if (!(e.getEntity() instanceof ServerPlayer p)) return;
        if (states.containsKey(p.getUUID())) return;  // 이미 빈사: 피해 그대로 (확인 사살 가능)
        if (e.getAmount() < p.getHealth()) return;
        if (!canGoDown(p, e.getSource())) return;
        try {
            goDown(p);
            e.setCanceled(true);
        } catch (Exception ex) {
            states.remove(p.getUUID());
            BodyCamLog.error("빈사 전환", ex);
        }
    }

    private static boolean canGoDown(ServerPlayer p, DamageSource source) {
        if (!BodyCamConfig.DOWNED_ENABLED.get()) return false;
        if (p.isCreative() || p.isSpectator()) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;  // 공허, /kill
        return !GameManager.isFfa();  // 개인전은 살려줄 팀원이 없음
    }

    private static void goDown(ServerPlayer p) {
        State st = new State();
        st.ticksLeft = BodyCamConfig.BLEED_SECONDS.get() * 20;
        states.put(p.getUUID(), st);
        p.setHealth(BodyCamConfig.DOWNED_HEALTH.get());
        p.stopUsingItem();
        p.setSprinting(false);
        p.setForcedPose(Pose.SWIMMING);
        sync(p.server);
        MatchLog.event("쓰러짐: " + p.getScoreboardName());
    }

    // 쓰러진 직후 잠깐 무적
    @SubscribeEvent
    public static void onAttack(LivingAttackEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            State st = states.get(p.getUUID());
            if (st != null && st.downedTicks < INVULNERABLE_TICKS) e.setCanceled(true);
        }
    }

    // ── 매 틱: 출혈, 치료, 포기 ──
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || states.isEmpty()) return;
        Safe.run("빈사 진행", DownedManager::tickDowned);
    }

    private static void tickDowned() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || GameManager.isPaused()) return;

        boolean changed = false;
        int reviveTicks = BodyCamConfig.REVIVE_SECONDS.get() * 20;

        for (Map.Entry<UUID, State> entry : new ArrayList<>(states.entrySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            State st = entry.getValue();
            if (p == null || !p.isAlive()) {
                states.remove(entry.getKey());
                changed = true;
                continue;
            }
            st.downedTicks++;
            p.setForcedPose(Pose.SWIMMING);

            // 포기: Shift 유지
            if (p.isShiftKeyDown()) {
                if (++st.giveUpTicks >= GIVE_UP_TICKS) {
                    states.remove(p.getUUID());
                    changed = true;
                    p.kill();
                    MatchLog.event("포기: " + p.getScoreboardName());
                    continue;
                }
            } else {
                st.giveUpTicks = 0;
            }

            // 치료: 팀원이 옆에서 웅크리면 진행, 치료 중엔 출혈 멈춤
            ServerPlayer helper = findReviver(p);
            if (helper != null) {
                String helperRole = GameManager.roleOf(helper);
                st.reviveProgress += BodyCamConfig.MEDIC_ROLE.get().equals(helperRole) ? 2 : 1;  // 의무병은 2배
                if (st.reviveProgress >= reviveTicks) {
                    revive(p, helper);
                    changed = true;
                    continue;
                }
                // 양쪽에 같은 진행도를 매 틱 표시
                int pct = st.reviveProgress * 100 / reviveTicks;
                helper.displayClientMessage(Component.literal("치료 중 " + pct + "%").withStyle(ChatFormatting.GREEN), true);
                p.displayClientMessage(Component.literal("치료받는 중 " + pct + "%").withStyle(ChatFormatting.GREEN), true);
            } else {
                st.reviveProgress = Math.max(0, st.reviveProgress - 2);
                if (--st.ticksLeft <= 0) {
                    states.remove(p.getUUID());
                    changed = true;
                    p.kill();
                    MatchLog.event("출혈사: " + p.getScoreboardName());
                    continue;
                }
            }

            // 출혈 안내 (치료 중이 아닐 때만) + 근처 팀원 안내
            if (st.downedTicks % 10 == 0) {
                if (helper == null) {
                    p.displayClientMessage(Component.literal("출혈 " + (st.ticksLeft + 19) / 20
                            + "초  ·  Shift를 3초 누르면 포기").withStyle(ChatFormatting.RED), true);
                }
                hintTeammates(p, helper);
            }
        }
        if (changed) sync(server);
    }

    // 근처 팀원에게 치료 방법 안내
    private static void hintTeammates(ServerPlayer downed, ServerPlayer helper) {
        String name = downed.getScoreboardName();
        for (ServerPlayer o : downed.serverLevel().players()) {
            if (o == downed || o == helper || !o.isAlive() || o.isSpectator() || states.containsKey(o.getUUID())) continue;
            if (!sameSide(o, downed)) continue;
            double d = o.distanceToSqr(downed);
            if (d > HINT_RANGE_SQ) continue;
            String text = d <= REVIVE_RANGE_SQ
                    ? "Shift를 누르고 있으면 " + name + " 치료"
                    : name + " 쓰러짐 · 가까이 가서 Shift로 치료";
            o.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.YELLOW), true);
        }
    }

    private static ServerPlayer findReviver(ServerPlayer p) {
        for (ServerPlayer o : p.serverLevel().players()) {
            if (o == p || !o.isAlive() || o.isSpectator() || states.containsKey(o.getUUID())) continue;
            if (!o.isShiftKeyDown() || o.distanceToSqr(p) > REVIVE_RANGE_SQ) continue;
            if (!sameSide(o, p)) continue;
            return o;
        }
        return null;
    }

    private static boolean sameSide(Player a, Player b) {
        Team ta = a.getTeam();
        Team tb = b.getTeam();
        if (ta == null || tb == null) return ta == tb;  // 둘 다 팀이 없을 때만 허용
        return ta.getName().equals(tb.getName());
    }

    private static void revive(ServerPlayer p, ServerPlayer helper) {
        states.remove(p.getUUID());
        GameManager.recordRevive(helper);
        MatchLog.event("구조: " + helper.getScoreboardName() + " → " + p.getScoreboardName());
        p.setForcedPose(null);
        p.setPose(Pose.STANDING);
        p.setHealth(BodyCamConfig.REVIVE_HEALTH.get());
        p.displayClientMessage(Component.literal(helper.getScoreboardName() + "님이 치료했습니다").withStyle(ChatFormatting.GREEN), true);
        helper.displayClientMessage(Component.literal(p.getScoreboardName() + " 치료 완료").withStyle(ChatFormatting.GREEN), true);
    }

    // 경기 종료 등: 쓰러진 사람 전부 일으키기
    public static void releaseAll(MinecraftServer server) {
        if (states.isEmpty()) return;
        for (UUID id : states.keySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) {
                p.setForcedPose(null);
                p.setPose(Pose.STANDING);
                p.setHealth(BodyCamConfig.REVIVE_HEALTH.get());
            }
        }
        states.clear();
        sync(server);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && states.remove(p.getUUID()) != null) sync(p.server);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof ServerPlayer p && states.remove(p.getUUID()) != null) {
            p.setForcedPose(null);
            sync(p.server);
        }
    }

    // 새로 들어온 사람에게 현재 목록 전송
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) {
            BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                    new DownedSyncPacket(new ArrayList<>(states.keySet())));
        }
    }

    private static void sync(MinecraftServer server) {
        BodyCamNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new DownedSyncPacket(new ArrayList<>(states.keySet())));
    }
}