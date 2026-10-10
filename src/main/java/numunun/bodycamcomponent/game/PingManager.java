package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.network.PacketDistributor;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.PingPacket;
import numunun.bodycamcomponent.network.PingRequestPacket;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

// 핑: 요청 검증 후 같은 팀에게만 전달 + 정찰 지원 자동 적 식별
public final class PingManager {
    private static final int COOLDOWN_TICKS = 10;       // 0.5초
    private static final double MAX_RANGE = 256.0;
    private static final int BASE_LIFETIME_MS = 6000;
    private static final double SPOT_RANGE = 160.0;
    private static final int SPOT_COOLDOWN_TICKS = 60;  // 정찰 지원 연속 식별 간격 (3초)

    private record Spot(UUID target, int ticks) {}

    private static final Map<UUID, Long> lastPing = new HashMap<>();
    private static final Map<UUID, Spot> spotting = new HashMap<>();
    private static final Map<UUID, Long> lastSpot = new HashMap<>();

    private PingManager() {}

    public static void handle(ServerPlayer sender, PingRequestPacket msg) {
        if (sender.isSpectator() || !sender.isAlive()) return;

        // 1) 좌표 검증: NaN, 무한대 차단 (거리 비교를 우회할 수 있음)
        if (!Double.isFinite(msg.x()) || !Double.isFinite(msg.y()) || !Double.isFinite(msg.z())) return;

        long now = sender.server.overworld().getGameTime();
        Long last = lastPing.get(sender.getUUID());
        if (last != null && now - last < COOLDOWN_TICKS) return;
        lastPing.put(sender.getUUID(), now);

        Vec3 eye = sender.getEyePosition();
        Vec3 pos = new Vec3(msg.x(), msg.y(), msg.z());
        if (pos.distanceToSqr(eye) > MAX_RANGE * MAX_RANGE) return;

        // 2) 적 핑: 거리 안이고 실제로 보이는 적만 인정 (벽 너머 적 위치 노출 차단)
        boolean enemy = false;
        if (msg.entityId() >= 0) {
            Entity e = sender.level().getEntity(msg.entityId());
            if (e instanceof ServerPlayer target && target != sender && !target.isSpectator()
                    && !sameTeam(target, sender)
                    && target.distanceToSqr(sender) <= MAX_RANGE * MAX_RANGE
                    && canSee(sender, target)) {
                enemy = true;
                pos = target.position().add(0, target.getBbHeight() + 0.3, 0);
            }
        }

        // 3) 위치 핑: 시선이 실제로 닿는 지점인지 (벽 너머 좌표 차단)
        if (!enemy && !isVisiblePoint(sender, eye, pos)) return;

        broadcastPing(sender, pos, enemy);
    }

    // 머리, 몸통, 발 중 하나라도 블록에 안 막히고 보이면 true
    private static boolean canSee(ServerPlayer viewer, ServerPlayer target) {
        Vec3 eye = viewer.getEyePosition();
        Vec3[] points = {
                target.getEyePosition(),
                target.position().add(0, target.getBbHeight() * 0.5, 0),
                target.position().add(0, 0.2, 0)
        };
        for (Vec3 p : points) {
            BlockHitResult hit = viewer.level().clip(new ClipContext(eye, p,
                    ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, viewer));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }

    // 보낸 지점이 시선이 처음 닿는 지점 근처인지 (클라이언트와 같은 방식으로 다시 계산)
    private static boolean isVisiblePoint(ServerPlayer sender, Vec3 eye, Vec3 pos) {
        BlockHitResult hit = sender.level().clip(new ClipContext(eye, pos,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, sender));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(pos) < 1.5 * 1.5;
    }

    // 작전관의 핑은 2배 오래 남음
    private static void broadcastPing(ServerPlayer sender, Vec3 pos, boolean enemy) {
        int lifetime = GameManager.isOperator(sender) ? BASE_LIFETIME_MS * 2 : BASE_LIFETIME_MS;
        PingPacket out = new PingPacket(sender.getScoreboardName(), pos.x, pos.y, pos.z, enemy, lifetime);
        for (ServerPlayer p : sender.server.getPlayerList().getPlayers()) {
            if (p == sender || sameTeam(p, sender)) {
                BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), out);
            }
        }
    }

    // ── 정찰 지원: 망원경으로 적을 일정 시간 보면 자동 적 핑 ──
    public static void tickSpotting(MinecraftServer server) {
        if (!GameManager.isRunning() || GameManager.isPaused()) {
            spotting.clear();
            return;
        }
        int need = (int) Math.round(BodyCamConfig.RECON_SPOT_SECONDS.get() * 20);
        long now = server.overworld().getGameTime();

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            if (!p.isScoping() || p.isSpectator() || !GameManager.isRecon(p)) {
                spotting.remove(id);
                continue;
            }
            ServerPlayer target = findLookTarget(p);
            if (target == null) {
                spotting.remove(id);
                continue;
            }

            Spot s = spotting.get(id);
            int ticks = s != null && s.target().equals(target.getUUID()) ? s.ticks() + 1 : 1;
            if (ticks < need) {
                spotting.put(id, new Spot(target.getUUID(), ticks));
                continue;
            }

            spotting.put(id, new Spot(target.getUUID(), 0));  // 계속 보고 있으면 다시 쌓임
            Long last = lastSpot.get(id);
            if (last != null && now - last < SPOT_COOLDOWN_TICKS) continue;
            lastSpot.put(id, now);

            broadcastPing(p, target.position().add(0, target.getBbHeight() + 0.3, 0), true);
            p.displayClientMessage(Component.literal("적 위치 공유").withStyle(ChatFormatting.RED), true);
        }
    }

    // 시선 끝에 있는 적 플레이어 (벽에 가려지면 없음)
    private static ServerPlayer findLookTarget(ServerPlayer p) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1f);
        Vec3 end = eye.add(look.scale(SPOT_RANGE));

        BlockHitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        Vec3 limit = block.getType() == HitResult.Type.MISS ? end : block.getLocation();

        AABB box = p.getBoundingBox().expandTowards(look.scale(SPOT_RANGE)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(p, eye, limit, box,
                e -> e instanceof ServerPlayer o && o != p && !o.isSpectator()
                        && GameManager.isParticipant(o) && !sameTeam(o, p),
                eye.distanceToSqr(limit));
        return hit != null ? (ServerPlayer) hit.getEntity() : null;
    }

    private static boolean sameTeam(ServerPlayer a, ServerPlayer b) {
        Team ta = a.getTeam();
        Team tb = b.getTeam();
        return ta != null && tb != null && ta.getName().equals(tb.getName());
    }
}