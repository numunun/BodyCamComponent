package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import numunun.bodycamcomponent.loadout.LoadoutManager;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GameManager {
    private static final int KILL_CREDIT_TICKS = 20 * 180;  // 마지막 공격 후 킬 인정 시간 (3분)
    private static final String OBJECTIVE = "bodycam";

    private static boolean running = false;
    private static GameMode mode = GameMode.TDM;
    private static int target = 30;
    private static final Map<String, Integer> teamScores = new HashMap<>();
    private static final Map<UUID, Integer> playerKills = new HashMap<>();
    private static final Map<UUID, Attack> lastAttack = new HashMap<>();

    private record Attack(UUID attacker, long time) {}

    private GameManager() {}

    public static boolean isRunning() { return running; }

    // ── 시작 조건 검사 (문제 있으면 메시지, 없으면 null) ──
    @Nullable
    public static String validate(MinecraftServer server) {
        GameSettings s = GameSettings.get(server);
        if (s.mode == GameMode.FFA) {
            if (s.field == null) return "개인전 필드가 지정되지 않았습니다. (/bodycam field set)";
            if (server.getLevel(s.field.dimension()) == null) return "필드가 있는 차원을 찾을 수 없습니다.";
        } else {
            Collection<PlayerTeam> teams = server.getScoreboard().getPlayerTeams();
            if (teams.size() < 2) return "팀전은 팀이 2개 이상 필요합니다. (/bodycam team setup)";
            for (PlayerTeam t : teams) {
                Region r = s.teamSpawns.get(t.getName());
                if (r == null) {
                    return "'" + t.getName() + "' 팀의 스폰 범위가 지정되지 않았습니다. (/bodycam spawn set " + t.getName() + ")";
                }
                if (server.getLevel(r.dimension()) == null) {
                    return "'" + t.getName() + "' 팀 스폰 범위의 차원을 찾을 수 없습니다.";
                }
            }
        }
        return null;
    }

    // ── 경기 시작 ──
    @Nullable
    public static String start(MinecraftServer server) {
        if (running) return "이미 경기가 진행 중입니다.";
        String err = validate(server);
        if (err != null) return err;

        GameSettings s = GameSettings.get(server);
        mode = s.mode;
        target = s.targetKills;
        teamScores.clear();
        playerKills.clear();
        lastAttack.clear();
        running = true;
        LoadoutManager.setEnabled(true);

        setupSidebar(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isParticipant(p)) spawnParticipant(p);
        }
        broadcast(server, Component.literal("경기 시작! " + mode.label + " / 목표 " + target + "킬")
                .withStyle(ChatFormatting.YELLOW));
        return null;
    }

    public static boolean stop(MinecraftServer server) {
        if (!running) return false;
        reset();
        broadcast(server, Component.literal("경기가 중단되었습니다.").withStyle(ChatFormatting.GRAY));
        return true;
    }

    public static void reset() {
        running = false;
        lastAttack.clear();
        LoadoutManager.setEnabled(false);
    }

    // ── 참가자 판정 ──
    public static boolean isParticipant(ServerPlayer p) {
        if (!running || p.isSpectator()) return false;
        if (mode == GameMode.FFA) return true;
        Team t = p.getTeam();
        return t != null && GameSettings.get(p.server).teamSpawns.containsKey(t.getName());
    }

    @Nullable
    private static Region regionFor(ServerPlayer p) {
        GameSettings s = GameSettings.get(p.server);
        if (mode == GameMode.FFA) return s.field;
        Team t = p.getTeam();
        return t == null ? null : s.teamSpawns.get(t.getName());
    }

    // ── 스폰 ──
    private static void spawnParticipant(ServerPlayer p) {
        List<ItemStack> attachments = LoadoutManager.clearTargets(p);
        List<ItemStack> guns = LoadoutManager.giveRandomLoadout(p);
        LoadoutManager.installOrGive(p, guns, attachments);
        LoadoutManager.autoInstallFromInventory(p, guns);

        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.clearFire();
        teleportToSpawn(p);
    }

    private static void teleportToSpawn(ServerPlayer p) {
        Region r = regionFor(p);
        if (r == null) return;
        ServerLevel level = p.server.getLevel(r.dimension());
        if (level == null) return;
        BlockPos pos = r.findSafeSpawn(level, p.getRandom());
        if (pos == null) {
            p.sendSystemMessage(Component.literal("안전한 스폰 위치를 찾지 못했습니다. 구역을 확인하세요.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        p.teleportTo(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, p.getYRot(), 0f);
    }

    // 장비 지급은 LoadoutEvents가 처리하고, 여기선 위치만
    public static void onRespawn(ServerPlayer p) {
        if (isParticipant(p)) teleportToSpawn(p);
    }

    // ── 킬 판정 ──
    public static void recordAttack(ServerPlayer victim, ServerPlayer attacker) {
        if (!running) return;
        lastAttack.put(victim.getUUID(), new Attack(attacker.getUUID(), victim.server.overworld().getGameTime()));
    }

    public static void onDeath(ServerPlayer victim, DamageSource source) {
        if (!running) return;
        MinecraftServer server = victim.server;

        ServerPlayer killer = null;
        if (source.getEntity() instanceof ServerPlayer sp) {
            killer = sp;
        } else {
            Attack a = lastAttack.get(victim.getUUID());
            if (a != null && server.overworld().getGameTime() - a.time() <= KILL_CREDIT_TICKS) {
                killer = server.getPlayerList().getPlayer(a.attacker());
            }
        }
        lastAttack.remove(victim.getUUID());

        if (killer == null || killer == victim) return;
        if (!isParticipant(victim) || !isParticipant(killer)) return;

        if (mode == GameMode.TDM) {
            Team vt = victim.getTeam();
            Team kt = killer.getTeam();
            if (kt == null) return;
            if (vt != null && vt.getName().equals(kt.getName())) {
                broadcast(server, Component.literal(killer.getScoreboardName() + "님이 아군을 사살했습니다.")
                        .withStyle(ChatFormatting.GRAY));
                return;
            }
            playerKills.merge(killer.getUUID(), 1, Integer::sum);
            int score = teamScores.merge(kt.getName(), 1, Integer::sum);
            setSidebarScore(server, teamEntry(kt.getName()), score);
            if (score >= target) end(server, Component.literal(kt.getName().toUpperCase() + " 팀 승리"));
        } else {
            int kills = playerKills.merge(killer.getUUID(), 1, Integer::sum);
            setSidebarScore(server, killer.getScoreboardName(), kills);
            if (kills >= target) end(server, Component.literal(killer.getScoreboardName() + " 승리"));
        }
    }

    // ── 경기 종료 ──
    private static void end(MinecraftServer server, Component result) {
        reset();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 80, 20));
            p.connection.send(new ClientboundSetTitleTextPacket(Component.literal("경기 종료")));
            p.connection.send(new ClientboundSetSubtitleTextPacket(result));
        }
        broadcast(server, Component.literal("경기 종료: ").append(result).withStyle(ChatFormatting.GOLD));
    }

    // ── 점수판 ──
    private static String teamEntry(String teamName) {
        return teamName.toUpperCase();
    }

    private static void setupSidebar(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        Objective old = sb.getObjective(OBJECTIVE);
        if (old != null) sb.removeObjective(old);

        Objective obj = sb.addObjective(OBJECTIVE, ObjectiveCriteria.DUMMY,
                Component.literal("목표 " + target + "킬").withStyle(ChatFormatting.YELLOW),
                ObjectiveCriteria.RenderType.INTEGER);
        sb.setDisplayObjective(Scoreboard.DISPLAY_SLOT_SIDEBAR, obj);

        if (mode == GameMode.TDM) {
            for (PlayerTeam t : sb.getPlayerTeams()) {
                sb.getOrCreatePlayerScore(teamEntry(t.getName()), obj).setScore(0);
            }
        } else {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (isParticipant(p)) sb.getOrCreatePlayerScore(p.getScoreboardName(), obj).setScore(0);
            }
        }
    }

    private static void setSidebarScore(MinecraftServer server, String entry, int value) {
        Scoreboard sb = server.getScoreboard();
        Objective obj = sb.getObjective(OBJECTIVE);
        if (obj != null) sb.getOrCreatePlayerScore(entry, obj).setScore(value);
    }

    // ── 상태 ──
    public static String status(MinecraftServer server) {
        GameSettings s = GameSettings.get(server);
        StringBuilder sb = new StringBuilder();
        sb.append("진행 중: ").append(running ? "예" : "아니오").append('\n');
        sb.append("모드: ").append(s.mode.label).append(" / 목표: ").append(s.targetKills).append("킬\n");
        sb.append("개인전 필드: ").append(s.field == null ? "없음" : s.field.describe()).append('\n');
        sb.append("팀 스폰:");
        if (s.teamSpawns.isEmpty()) sb.append(" 없음");
        s.teamSpawns.forEach((k, v) -> sb.append("\n  ").append(k).append(": ").append(v.describe()));
        return sb.toString();
    }

    private static void broadcast(MinecraftServer server, Component msg) {
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }
}