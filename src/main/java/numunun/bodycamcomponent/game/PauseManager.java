package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.OsdPacket;
import numunun.bodycamcomponent.util.MatchLog;
import numunun.bodycamcomponent.util.Safe;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 일시정지, 재개 카운트다운, 시작 카운트다운, 제자리 고정, 접속/퇴장 시 자동 일시정지
final class PauseManager {
    private static final int RESUME_TICKS = 60;  // 재개 카운트다운 3초

    private static boolean paused = false;
    private static int resumeTicks = 0;
    private static boolean startPhase = false;   // 시작 카운트다운 중 (암전 연출)
    private static final Map<UUID, Vec3> frozenPos = new HashMap<>();
    private static final Set<UUID> pendingSpawn = new HashSet<>();  // 일시정지 중 들어온 사람

    private PauseManager() {}

    static boolean isPaused() {
        return paused;
    }

    static void reset() {
        paused = false;
        resumeTicks = 0;
        startPhase = false;
        frozenPos.clear();
        pendingSpawn.clear();
    }

    static boolean pause(MinecraftServer server, String reason) {
        if (paused) return false;
        paused = true;
        resumeTicks = 0;
        freezeParticipants(server);
        GameManager.sendOsd(OsdPacket.PAUSE, reason, 0);
        GameManager.broadcast(server, Component.literal("[일시 정지] " + reason).withStyle(ChatFormatting.YELLOW));
        sendResumeButton(server);
        MatchLog.event("일시 정지: " + reason);
        return true;
    }

    static boolean resume() {
        if (!paused || resumeTicks > 0) return false;
        startPhase = false;
        resumeTicks = RESUME_TICKS;
        GameManager.sendOsd(OsdPacket.PLAY, "", RESUME_TICKS / 20);
        MatchLog.event("재개 카운트다운");
        return true;
    }

    // 시작 전 제자리 고정 (일시정지 기능 재활용, OSD 없이 암전 연출만)
    static void startCountdown(MinecraftServer server, int seconds) {
        if (seconds <= 0) return;
        paused = true;
        startPhase = true;
        freezeParticipants(server);
        resumeTicks = seconds * 20;
    }

    // 일시정지 중이면 true (경기의 다른 진행은 멈춤)
    static boolean tick(MinecraftServer server) {
        if (!paused) return false;
        if (resumeTicks > 0 && --resumeTicks == 0) {
            finishResume(server);
            return true;
        }
        holdFrozen(server);
        return true;
    }

    private static void finishResume(MinecraftServer server) {
        MatchLog.event(startPhase ? "교전 시작" : "재개");
        paused = false;
        frozenPos.clear();
        // 일시정지 중 들어온 사람: 직급 배정, 스폰, 시작 연출
        for (UUID id : pendingSpawn) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && GameManager.isParticipant(p) && !RespawnManager.isWaiting(id)) {
                RoleManager.ensureRole(p);
                Safe.run("합류 스폰: " + p.getScoreboardName(), () -> GameManager.spawnParticipant(p));
                ReportManager.sendIntro(p, 0);
            }
        }
        pendingSpawn.clear();
        startPhase = false;
    }

    private static void freezeParticipants(MinecraftServer server) {
        frozenPos.clear();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (GameManager.isParticipant(p) && !RespawnManager.isWaiting(p.getUUID())) {
                frozenPos.put(p.getUUID(), p.position());
            }
        }
    }

    private static void holdFrozen(MinecraftServer server) {
        for (Map.Entry<UUID, Vec3> entry : frozenPos.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(entry.getKey());
            if (p == null) continue;
            Vec3 pos = entry.getValue();
            if (p.position().distanceToSqr(pos) > 0.25) {
                p.teleportTo(p.serverLevel(), pos.x, pos.y, pos.z, p.getYRot(), p.getXRot());
            }
        }
    }

    private static void sendResumeButton(MinecraftServer server) {
        Component button = Component.literal("[재개하기]").withStyle(s -> s
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/bodycam resume"))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("클릭하면 3초 뒤 경기 재개"))));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.hasPermissions(2)) p.sendSystemMessage(button);
        }
    }

    // ── 접속/퇴장 시 자동 일시정지 ──
    static void onLogin(ServerPlayer p) {
        pendingSpawn.add(p.getUUID());
        if (paused) {
            frozenPos.put(p.getUUID(), p.position());
            GameManager.broadcast(p.server, Component.literal(p.getScoreboardName() + "님이 접속했습니다. 재개하면 스폰됩니다.")
                    .withStyle(ChatFormatting.YELLOW));
            BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                    new OsdPacket(OsdPacket.PAUSE, "경기 일시정지 중", 0));
        } else {
            pause(p.server, p.getScoreboardName() + "님이 접속했습니다.");
            frozenPos.put(p.getUUID(), p.position());
        }
    }

    static void onLogout(ServerPlayer p) {
        frozenPos.remove(p.getUUID());
        pendingSpawn.remove(p.getUUID());
        if (paused) {
            GameManager.broadcast(p.server, Component.literal(p.getScoreboardName() + "님이 나갔습니다.")
                    .withStyle(ChatFormatting.YELLOW));
        } else {
            pause(p.server, p.getScoreboardName() + "님이 나갔습니다.");
        }
    }
}