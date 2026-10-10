package numunun.bodycamcomponent.game;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

// 킬 판정, 팀/개인 점수, 전투 기록 (작전 보고서용)
final class ScoreManager {
    private static final int KILL_CREDIT_TICKS = 20 * 180;  // 마지막 공격 후 킬 인정 시간 (3분)
    private static final String OLD_OBJECTIVE = "bodycam";   // 예전 사이드바 점수판 이름

    private record Attack(UUID attacker, long time) {}

    private static final Map<String, Integer> teamScores = new HashMap<>();
    private static final Map<UUID, Integer> playerKills = new HashMap<>();
    private static final Map<UUID, Integer> playerDeaths = new HashMap<>();
    private static final Map<UUID, Integer> playerRevives = new HashMap<>();
    private static final Map<UUID, String> playerNames = new HashMap<>();
    private static final Map<UUID, String> playerTeams = new HashMap<>();
    private static final Map<UUID, Attack> lastAttack = new HashMap<>();

    private ScoreManager() {}

    // 경기 시작 시 전부 초기화
    static void reset() {
        teamScores.clear();
        playerKills.clear();
        playerDeaths.clear();
        playerRevives.clear();
        playerNames.clear();
        playerTeams.clear();
        lastAttack.clear();
    }

    // 경기 종료 시: 공격 기록만 정리 (점수는 보고서를 위해 유지)
    static void clearAttacks() {
        lastAttack.clear();
    }

    // 예전 버전의 사이드바 점수판이 남아 있으면 정리
    static void removeOldSidebar(MinecraftServer server) {
        Scoreboard sb = server.getScoreboard();
        Objective old = sb.getObjective(OLD_OBJECTIVE);
        if (old != null) sb.removeObjective(old);
    }

    // ── 기록 ──
    static void remember(ServerPlayer p) {
        playerNames.put(p.getUUID(), p.getScoreboardName());
        Team t = p.getTeam();
        if (t != null) playerTeams.put(p.getUUID(), t.getName());
    }

    static void recordAttack(ServerPlayer victim, ServerPlayer attacker) {
        lastAttack.put(victim.getUUID(), new Attack(attacker.getUUID(), victim.server.overworld().getGameTime()));
    }

    static void recordRevive(ServerPlayer helper) {
        playerRevives.merge(helper.getUUID(), 1, Integer::sum);
    }

    static void recordDeath(ServerPlayer victim) {
        remember(victim);
        playerDeaths.merge(victim.getUUID(), 1, Integer::sum);
    }

    // 직접 죽인 사람, 없으면 제한 시간 내 마지막 공격자
    @Nullable
    static ServerPlayer resolveKiller(ServerPlayer victim, DamageSource source) {
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
        return killer;
    }

    // ── 킬 점수 ──
    static void onKill(ServerPlayer victim, @Nullable ServerPlayer killer) {
        if (killer == null || killer == victim || !GameManager.isParticipant(killer)) return;
        MinecraftServer server = victim.server;
        GameMode mode = GameManager.mode();

        if (mode.isTeamMode()) {
            Team vt = victim.getTeam();
            Team kt = killer.getTeam();
            if (kt == null) return;
            if (vt != null && vt.getName().equals(kt.getName())) return;  // 아군 사살: 점수 없음

            playerKills.merge(killer.getUUID(), 1, Integer::sum);
            if (mode == GameMode.DOM) return;  // 점령전: 킬은 기록만
            int score = teamScores.merge(kt.getName(), 1, Integer::sum);
            if (score >= GameManager.target()) {
                GameManager.end(server, Component.literal(GameManager.teamDisplay(server, kt.getName()) + " 승리"));
            }
        } else {
            int kills = playerKills.merge(killer.getUUID(), 1, Integer::sum);
            if (kills >= GameManager.target()) {
                GameManager.end(server, Component.literal(killer.getScoreboardName() + " 승리"));
            }
        }
    }

    static int addTeamScore(String team, int amount) {
        return teamScores.merge(team, amount, Integer::sum);
    }

    // ── 조회 (작전 보고서, 직급 배정용) ──
    static Set<UUID> players() { return playerNames.keySet(); }

    static Collection<String> teams() { return playerTeams.values(); }

    static String nameOf(UUID id) { return playerNames.getOrDefault(id, "?"); }

    @Nullable
    static String teamOf(UUID id) { return playerTeams.get(id); }

    static int teamScore(String team) { return teamScores.getOrDefault(team, 0); }

    static int kills(UUID id) { return playerKills.getOrDefault(id, 0); }

    static int deaths(UUID id) { return playerDeaths.getOrDefault(id, 0); }

    static int revives(UUID id) { return playerRevives.getOrDefault(id, 0); }
}