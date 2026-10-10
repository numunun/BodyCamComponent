package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraftforge.network.PacketDistributor;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.downed.DownedManager;
import numunun.bodycamcomponent.entity.CorpseEntity;
import numunun.bodycamcomponent.loadout.LoadoutManager;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.OsdPacket;
import numunun.bodycamcomponent.registry.ModEntities;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;

// 경기 진행의 중심: 시작/종료, 참가자 판정, 스폰, 다른 패키지를 위한 외부 창구.
// 세부 기능은 PauseManager, RespawnManager, ScoreManager, RoleManager, ReportManager가 담당
public final class GameManager {
    private static boolean running = false;
    private static GameMode mode = GameMode.TDM;
    private static int target = 30;
    private static boolean prevImmediateRespawn = false;
    private static boolean prevShowDeathMessages = true;

    private GameManager() {}

    // ── 상태 ──
    public static boolean isRunning() { return running; }

    public static boolean isFfa() { return running && mode == GameMode.FFA; }

    static GameMode mode() { return mode; }

    static int target() { return target; }

    // ── 외부 창구: 일시정지 ──
    public static boolean isPaused() { return running && PauseManager.isPaused(); }

    public static boolean pause(MinecraftServer server, String reason) {
        return running && PauseManager.pause(server, reason);
    }

    public static boolean resume(MinecraftServer server) {
        return running && PauseManager.resume();
    }

    // ── 외부 창구: 직급 ──
    @Nullable
    public static String roleOf(ServerPlayer p) { return running ? RoleManager.roleOf(p) : null; }

    public static boolean isLeader(ServerPlayer p) { return running && RoleManager.isLeader(p); }

    public static boolean isOperator(ServerPlayer p) { return running && RoleManager.isOperator(p); }

    public static boolean isRecon(ServerPlayer p) { return running && RoleManager.isRecon(p); }

    // ── 외부 창구: 전투 기록 ──
    public static void recordAttack(ServerPlayer victim, ServerPlayer attacker) {
        if (running) ScoreManager.recordAttack(victim, attacker);
    }

    // 빈사 상태 팀원을 살렸을 때 (DownedManager에서 호출)
    public static void recordRevive(ServerPlayer helper) {
        if (running) ScoreManager.recordRevive(helper);
    }

    public static void onDeath(ServerPlayer victim, DamageSource source) {
        if (!running) return;
        ServerPlayer killer = ScoreManager.resolveKiller(victim, source);
        if (!isParticipant(victim)) return;
        ScoreManager.recordDeath(victim);
        RespawnManager.recordDeath(victim, killer);
        ScoreManager.onKill(victim, killer);
    }

    static int addTeamScore(MinecraftServer server, String team, int amount) {
        return ScoreManager.addTeamScore(team, amount);
    }

    // ── 외부 창구: 접속, 리스폰 ──
    public static void onRespawn(ServerPlayer p) { RespawnManager.onRespawn(p); }

    public static void onLogin(ServerPlayer p) {
        if (running) PauseManager.onLogin(p);
    }

    public static void onLogout(ServerPlayer p) {
        RespawnManager.onLogout(p);
        if (running) PauseManager.onLogout(p);
    }

    // ── 매 틱 ──
    public static void tick(MinecraftServer server) {
        if (!running) return;
        if (PauseManager.tick(server)) return;  // 일시정지 중에는 리스폰 대기, 점령도 멈춤
        if (mode == GameMode.DOM) {
            DominationManager.tick(server);
            if (!running) return;  // 점령전 승리로 경기 종료
        }
        RespawnManager.tick(server);
    }

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
            if (s.mode == GameMode.DOM) {
                if (s.points.isEmpty()) return "점령전 거점이 없습니다. (/bodycam point add <이름>)";
                for (GameSettings.CapturePoint pt : s.points) {
                    if (server.getLevel(pt.dim()) == null) return "거점 " + pt.name() + "의 차원을 찾을 수 없습니다.";
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
        target = mode == GameMode.DOM ? s.domTargetScore : s.targetKills;
        ScoreManager.reset();
        RoleManager.reset();
        RespawnManager.reset(BodyCamConfig.RESPAWN_DELAY.get() * 20);
        ReportManager.begin();
        running = true;
        LoadoutManager.setEnabled(true);

        // 사망 화면 건너뛰기
        GameRules.BooleanValue rule = server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN);
        prevImmediateRespawn = rule.get();
        rule.set(true, server);

        // 바닐라 사망 메시지 끄기
        GameRules.BooleanValue deathMsg = server.getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES);
        prevShowDeathMessages = deathMsg.get();
        deathMsg.set(false, server);

        clearCorpses(server);
        ScoreManager.removeOldSidebar(server);
        if (mode == GameMode.DOM) DominationManager.start(server);
        RoleManager.assign(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isParticipant(p)) spawnParticipant(p);
        }
        broadcast(server, Component.literal("[작전 개시] " + mode.label).withStyle(ChatFormatting.GRAY));

        int countdown = BodyCamConfig.START_COUNTDOWN.get();
        ReportManager.sendIntros(server, countdown);
        PauseManager.startCountdown(server, countdown);
        return null;
    }

    // 지난 경기 시체 정리
    private static void clearCorpses(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (CorpseEntity corpse : level.getEntities(ModEntities.CORPSE.get(), c -> true)) {
                corpse.discard();
            }
        }
    }

    // ── 경기 중단, 종료 ──
    public static boolean stop(MinecraftServer server) {
        if (!running) return false;
        reset(server);
        sendOsd(OsdPacket.STOP, "작전 중단", 0);
        broadcast(server, Component.literal("[작전 중단]").withStyle(ChatFormatting.GRAY));
        return true;
    }

    // 디버그: 경기를 바로 끝내고 작전 보고서까지 출력
    public static boolean forceEnd(MinecraftServer server, @Nullable String winnerTeam) {
        if (!running) return false;
        Component result = winnerTeam != null
                ? Component.literal(teamDisplay(server, winnerTeam) + " 승리")
                : Component.literal("강제 종료");
        end(server, result);
        return true;
    }

    static void end(MinecraftServer server, Component result) {
        reset(server);
        sendOsd(OsdPacket.STOP, result.getString(), 0);
        broadcast(server, Component.literal("[작전 종료] ").withStyle(ChatFormatting.GRAY).append(result));
        ReportManager.announce(server, result);
    }

    // 경기 상태 정리 (점수, 직급 기록은 작전 보고서를 위해 다음 경기 시작 전까지 유지)
    public static void reset(MinecraftServer server) {
        boolean wasRunning = running;
        running = false;
        RoleManager.clear(server);
        DominationManager.stop();
        DownedManager.releaseAll(server);
        PauseManager.reset();
        ScoreManager.clearAttacks();
        LoadoutManager.setEnabled(false);
        RespawnManager.releaseAll(server);

        if (wasRunning) {
            server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(prevImmediateRespawn, server);
            server.getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(prevShowDeathMessages, server);
        }
    }

    // ── 참가자 판정 (리스폰 대기 중인 관전자도 참가자) ──
    public static boolean isParticipant(ServerPlayer p) {
        if (!running) return false;
        if (p.isSpectator() && !RespawnManager.isWaiting(p.getUUID())) return false;
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
    // 경기 시작, 중간 합류 시: 장비를 새로 받고 스폰
    static void spawnParticipant(ServerPlayer p) {
        ScoreManager.remember(p);
        List<ItemStack> attachments = LoadoutManager.clearTargets(p);
        List<ItemStack> guns = LoadoutManager.giveRandomLoadout(p);
        LoadoutManager.installOrGive(p, guns, attachments);
        LoadoutManager.autoInstallFromInventory(p, guns);
        LoadoutManager.giveRoleKit(p, RoleManager.roleOf(p));
        RoleManager.applyLeaderHealth(p);
        heal(p);
        teleportToSpawn(p);
        RoleManager.sendAbility(p);
    }

    // 리스폰 대기가 끝났을 때: 보관품을 돌려받고 스폰
    static void respawnParticipant(ServerPlayer p) {
        List<ItemStack> guns = LoadoutManager.giveRandomLoadout(p);
        LoadoutManager.restoreAfterRespawn(p, guns);
        LoadoutManager.giveRoleKit(p, RoleManager.roleOf(p));
        RoleManager.applyLeaderHealth(p);
        heal(p);
        teleportToSpawn(p);
    }

    private static void heal(ServerPlayer p) {
        p.setHealth(p.getMaxHealth());
        p.getFoodData().setFoodLevel(20);
        p.clearFire();
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

    // ── 상태 표시 ──
    public static String status(MinecraftServer server) {
        GameSettings s = GameSettings.get(server);
        StringBuilder sb = new StringBuilder();
        sb.append("진행 중: ").append(running ? (PauseManager.isPaused() ? "일시 정지" : "예") : "아니오").append('\n');
        sb.append("모드: ").append(s.mode.label).append(" / 목표: ").append(s.targetKills).append("킬")
                .append(" / 리스폰 대기: ").append(BodyCamConfig.RESPAWN_DELAY.get()).append("초\n");
        sb.append("개인전 필드: ").append(s.field == null ? "없음" : s.field.describe()).append('\n');
        sb.append("팀 스폰:");
        if (s.teamSpawns.isEmpty()) sb.append(" 없음");
        s.teamSpawns.forEach((k, v) -> sb.append("\n  ").append(k).append(": ").append(v.describe()));
        sb.append("\n점령전 목표: ").append(s.domTargetScore).append("점 / 거점: ");
        if (s.points.isEmpty()) sb.append("없음");
        for (GameSettings.CapturePoint pt : s.points) sb.append(pt.name()).append(' ');
        return sb.toString();
    }

    // ── 공용 도우미 ──
    static void broadcast(MinecraftServer server, Component msg) {
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }

    static void sendOsd(int kind, String sub, int seconds) {
        BodyCamNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new OsdPacket(kind, sub, seconds));
    }

    // 팀 표시 이름 (내부 이름 blue → 화면 이름 케스트렐)
    static String teamDisplay(MinecraftServer server, String teamName) {
        PlayerTeam t = server.getScoreboard().getPlayerTeam(teamName);
        return t != null ? t.getDisplayName().getString() : teamName.toUpperCase();
    }
}