package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import numunun.bodycamcomponent.loadout.LoadoutManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 리스폰 대기: 관전(팀전은 팀원, 개인전은 나를 죽인 사람), 대상 없으면 사망 지점 고정
final class RespawnManager {
    private record DeathInfo(ResourceKey<Level> dim, Vec3 pos, @Nullable UUID killer) {}

    private static final class Waiting {
        int ticks;
        final GameType prevMode;
        @Nullable final DeathInfo death;

        Waiting(int ticks, GameType prevMode, @Nullable DeathInfo death) {
            this.ticks = ticks;
            this.prevMode = prevMode;
            this.death = death;
        }
    }

    private static int delayTicks = 100;
    private static final Map<UUID, DeathInfo> deathInfo = new HashMap<>();
    private static final Map<UUID, Waiting> waiting = new HashMap<>();

    private RespawnManager() {}

    static boolean isWaiting(UUID id) {
        return waiting.containsKey(id);
    }

    static void reset(int delay) {
        delayTicks = delay;
        deathInfo.clear();
        waiting.clear();
    }

    static void recordDeath(ServerPlayer victim, @Nullable ServerPlayer killer) {
        deathInfo.put(victim.getUUID(), new DeathInfo(victim.level().dimension(), victim.position(),
                killer != null && killer != victim ? killer.getUUID() : null));
    }

    // ── 리스폰 직후: 대기(관전) 시작 ──
    static void onRespawn(ServerPlayer p) {
        if (!GameManager.isParticipant(p)) return;
        DeathInfo d = deathInfo.remove(p.getUUID());
        GameType prev = p.gameMode.getGameModeForPlayer();

        if (delayTicks <= 0) {
            finishWaiting(p, prev);
            return;
        }
        waiting.put(p.getUUID(), new Waiting(delayTicks, prev, d));
        p.setGameMode(GameType.SPECTATOR);
        if (d != null) anchor(p, d);
    }

    private static void finishWaiting(ServerPlayer p, GameType prev) {
        p.setCamera(p);
        if (p.isSpectator()) p.setGameMode(prev);
        GameManager.respawnParticipant(p);
    }

    // 접속을 끊으면 원래 게임 모드로 (경기 중이 아니어도 실행)
    static void onLogout(ServerPlayer p) {
        Waiting w = waiting.remove(p.getUUID());
        if (w != null) {
            p.setCamera(p);
            p.setGameMode(w.prevMode);
        }
    }

    // 경기 종료: 대기 중인 사람 전부 원상 복구
    static void releaseAll(MinecraftServer server) {
        for (UUID id : new ArrayList<>(waiting.keySet())) {
            Waiting w = waiting.remove(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && w != null) {
                p.setCamera(p);
                p.setGameMode(w.prevMode);
                LoadoutManager.restoreAfterRespawn(p, List.of());
            }
        }
        waiting.clear();
        deathInfo.clear();
    }

    // ── 매 틱: 대기 시간 진행, 관전 대상 유지 ──
    static void tick(MinecraftServer server) {
        if (waiting.isEmpty()) return;
        Iterator<Map.Entry<UUID, Waiting>> it = waiting.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Waiting> entry = it.next();
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            Waiting w = entry.getValue();
            if (p == null) {
                it.remove();
                continue;
            }

            w.ticks--;
            if (w.ticks <= 0) {
                it.remove();
                finishWaiting(p, w.prevMode);
                continue;
            }

            ServerPlayer view = spectateTarget(server, p, w);
            if (view != null) {
                if (p.getCamera() != view) p.setCamera(view);
            } else {
                if (p.getCamera() != p) p.setCamera(p);
                if (w.death != null) anchor(p, w.death);
            }

            if (w.ticks % 10 == 0) {
                int sec = (w.ticks + 19) / 20;
                String msg = "리스폰까지 " + sec + "초"
                        + (view != null ? "  ·  관전: " + view.getScoreboardName() : "");
                p.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GRAY), true);
            }
        }
    }

    @Nullable
    private static ServerPlayer spectateTarget(MinecraftServer server, ServerPlayer p, Waiting w) {
        // 지금 보는 대상이 유효하면 유지
        if (p.getCamera() instanceof ServerPlayer cur && cur != p && isValidTarget(p, cur)) return cur;

        List<ServerPlayer> candidates = new ArrayList<>();
        if (GameManager.mode().isTeamMode()) {
            for (ServerPlayer o : server.getPlayerList().getPlayers()) {
                if (o != p && isValidTarget(p, o)) candidates.add(o);
            }
        } else if (w.death != null && w.death.killer() != null) {
            ServerPlayer killer = server.getPlayerList().getPlayer(w.death.killer());
            if (killer != null && isValidTarget(p, killer)) candidates.add(killer);
        }
        return candidates.isEmpty() ? null : candidates.get(p.getRandom().nextInt(candidates.size()));
    }

    private static boolean isValidTarget(ServerPlayer viewer, ServerPlayer o) {
        if (!o.isAlive() || waiting.containsKey(o.getUUID()) || !GameManager.isParticipant(o)) return false;
        if (GameManager.mode().isTeamMode()) {
            Team a = viewer.getTeam();
            Team b = o.getTeam();
            return a != null && b != null && a.getName().equals(b.getName());
        }
        return true;
    }

    // 관전 대상이 없을 때 죽은 자리에 고정
    private static void anchor(ServerPlayer p, DeathInfo d) {
        ServerLevel level = p.server.getLevel(d.dim());
        if (level == null) return;
        Vec3 pos = d.pos();
        if (p.level() != level || p.position().distanceToSqr(pos) > 1.0) {
            p.teleportTo(level, pos.x, pos.y, pos.z, p.getYRot(), p.getXRot());
        }
    }
}