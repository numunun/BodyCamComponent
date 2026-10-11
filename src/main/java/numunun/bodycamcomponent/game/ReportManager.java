package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraftforge.network.PacketDistributor;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.IntroPacket;
import numunun.bodycamcomponent.util.MatchLog;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;

// 경기 시작 연출 전송, 경기 종료 후 작전 보고서
final class ReportManager {
    private static final String[] CODE_A = {
            "오리온", "타이탄", "아틀라스", "하데스", "피닉스", "히드라", "아르테미스", "헬리오스",
            "사일런트", "아이언", "고스트", "크림슨", "프로즌", "노던", "할로우", "스틸"
    };
    private static final String[] CODE_B = {
            "스피어", "해머", "랜스", "블레이드", "팽", "서펀트", "이클립스",
            "스톰", "게이트", "베일", "앵커", "호라이즌", "레퀴엠", "바스티온"
    };

    private static long startedAtMs = 0;
    private static String codename = "";

    private ReportManager() {}

    // 경기 시작: 작전명 정하고 시간 기록
    static void begin() {
        startedAtMs = System.currentTimeMillis();
        Random rnd = new Random();
        codename = CODE_A[rnd.nextInt(CODE_A.length)] + " " + CODE_B[rnd.nextInt(CODE_B.length)] + " 작전";
    }

    static long durationMs() {
        return System.currentTimeMillis() - startedAtMs;
    }

    static String codename() {
        return codename;
    }

    // ── 시작 연출 ──
    static void sendIntros(MinecraftServer server, int blackSeconds) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (GameManager.isParticipant(p)) sendIntro(p, blackSeconds);
        }
    }

    static void sendIntro(ServerPlayer p, int blackSeconds) {
        String teamLine;
        int color = 0xFFFFFF;
        Team t = p.getTeam();
        if (GameManager.mode().isTeamMode() && t != null) {
            teamLine = BodyCamConfig.INTRO_TEAM_FORMAT.get().replace("{team}", GameManager.teamDisplay(p.server, t.getName()));
            PlayerTeam pt = p.server.getScoreboard().getPlayerTeam(t.getName());
            Integer c = pt != null ? pt.getColor().getColor() : null;
            if (c != null) color = c;
        } else {
            teamLine = BodyCamConfig.INTRO_FFA_TEXT.get();
        }
        String role = RoleManager.roleOf(p);
        BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                new IntroPacket(BodyCamConfig.INTRO_UNIT_LINE.get(), teamLine, color, role != null ? role : "",
                        p.getScoreboardName(), blackSeconds * 20));
    }

    // ── 작전 보고서 ──
    static void announce(MinecraftServer server, Component result) {
        if (ScoreManager.players().isEmpty()) return;
        GameMode mode = GameManager.mode();

        List<String> teams = new ArrayList<>(new LinkedHashSet<>(ScoreManager.teams()));
        teams.sort((a, b) -> Integer.compare(ScoreManager.teamScore(b), ScoreManager.teamScore(a)));

        List<UUID> ids = new ArrayList<>(ScoreManager.players());
        Map<UUID, Double> ratings = matchRatings(ids, teams);
        ids.sort((a, b) -> {
            int c = Double.compare(ratings.get(b), ratings.get(a));
            return c != 0 ? c : Integer.compare(ScoreManager.deaths(a), ScoreManager.deaths(b));
        });

        // 머리말
        send(server, Component.literal("──────── 작전 보고서 ────────").withStyle(ChatFormatting.GRAY));
        send(server, field("작전명", codename));
        send(server, field("소속", BodyCamConfig.INTRO_UNIT_LINE.get()));
        send(server, field("유형", mode.label + " · " + formatDuration(System.currentTimeMillis() - startedAtMs)));
        send(server, field("결과", result.getString() + scoreSummary(teams)));
        send(server, Component.literal("─────────────").withStyle(ChatFormatting.DARK_GRAY));

        // 부대별 전과
        if (mode.isTeamMode()) {
            for (String team : teams) {
                PlayerTeam pt = server.getScoreboard().getPlayerTeam(team);
                ChatFormatting color = pt != null ? pt.getColor() : ChatFormatting.WHITE;
                send(server, Component.literal("■ " + GameManager.teamDisplay(server, team) + "  "
                        + ScoreManager.teamScore(team) + unit()).withStyle(color, ChatFormatting.BOLD));
                for (UUID id : ids) {
                    if (team.equals(ScoreManager.teamOf(id))) send(server, resultLine("  ", id, ratings.get(id)));
                }
            }
        } else {
            int rank = 1;
            for (UUID id : ids) send(server, resultLine(" " + rank++ + ". ", id, ratings.get(id)));
        }

        // 최우수 요원: 이번 경기 평점 1등
        send(server, Component.literal("─────────────").withStyle(ChatFormatting.DARK_GRAY));
        UUID mvp = ids.get(0);
        String mvpRole = RoleManager.roleOf(mvp);
        send(server, Component.literal("최우수 요원  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal((mvpRole != null ? "[" + mvpRole + "] " : "") + ScoreManager.nameOf(mvp))
                        .withStyle(ChatFormatting.WHITE))
                .append(ratingText(ratings.get(mvp))));
    }

    // ── 경기 평점 (절대 평가, 0.0 ~ 10.0) ──
    // 10분 기준으로 환산한 활약을 정해진 기준으로 더하고 빼서, 0~10 곡선에 올림
    private static Map<UUID, Double> matchRatings(List<UUID> ids, List<String> teamsByScore) {
        double minutes = Math.max(3.0, durationMs() / 60000.0);
        double per10 = 10.0 / minutes;

        boolean teamMode = GameManager.mode().isTeamMode();
        String winTeam = null;
        boolean draw = false;
        UUID ffaTop = null;
        if (teamMode) {
            if (teamsByScore.size() >= 2
                    && ScoreManager.teamScore(teamsByScore.get(0)) == ScoreManager.teamScore(teamsByScore.get(1))) {
                draw = true;
            } else if (!teamsByScore.isEmpty()) {
                winTeam = teamsByScore.get(0);
            }
        } else {
            ffaTop = ids.stream().max((a, b) -> Integer.compare(ScoreManager.kills(a), ScoreManager.kills(b))).orElse(null);
        }

        Map<UUID, Double> out = new HashMap<>();
        for (UUID id : ids) {
            double raw = ScoreManager.kills(id) * per10 * 0.6
                    + ScoreManager.revives(id) * per10 * 0.4
                    - ScoreManager.deaths(id) * per10 * 0.4
                    - ScoreManager.teamKills(id) * 2.0
                    - 1.0;
            if (GameManager.mode() == GameMode.DOM) {
                raw += DominationManager.captureSeconds(id) / 60.0 * per10 * 0.5;
            }
            if (teamMode) raw += draw ? 0 : (winTeam != null && winTeam.equals(ScoreManager.teamOf(id)) ? 0.7 : -0.7);
            else if (id.equals(ffaTop)) raw += 0.7;

            double rating = 10.0 / (1.0 + Math.exp(-raw / 2.5));  // 0 ~ 10 곡선 (원점수 0이면 5.0)
            out.put(id, Math.round(rating * 10) / 10.0);
        }
        return out;
    }

    private static Component resultLine(String prefix, UUID id, double rating) {
        String role = RoleManager.roleOf(id);
        StringBuilder sb = new StringBuilder(prefix);
        if (role != null) sb.append('[').append(role).append("] ");
        sb.append(ScoreManager.nameOf(id))
                .append(" · 사살 ").append(ScoreManager.kills(id))
                .append(" · 손실 ").append(ScoreManager.deaths(id));
        int revives = ScoreManager.revives(id);
        if (revives > 0) sb.append(" · 구조 ").append(revives);
        if (GameManager.mode() == GameMode.DOM) {
            sb.append(" · 거점 ").append(formatDuration(DominationManager.captureSeconds(id) * 1000L));
        }
        return Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE).append(ratingText(rating));
    }

    // " · 평점 8.1"
    private static MutableComponent ratingText(double rating) {
        ChatFormatting color = rating >= 8.0 ? ChatFormatting.GOLD
                : rating >= 6.0 ? ChatFormatting.GREEN
                : rating >= 4.0 ? ChatFormatting.WHITE
                : rating >= 2.0 ? ChatFormatting.RED
                : ChatFormatting.DARK_RED;
        return Component.literal(" · 평점 ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.format("%.1f", rating)).withStyle(color, ChatFormatting.BOLD));
    }

    private static Component field(String label, String value) {
        return Component.literal(label + "  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.GRAY));
    }

    // 팀전 점수 요약: (30 : 24)
    private static String scoreSummary(List<String> teams) {
        if (!GameManager.mode().isTeamMode() || teams.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" (");
        for (int i = 0; i < teams.size(); i++) {
            if (i > 0) sb.append(" : ");
            sb.append(ScoreManager.teamScore(teams.get(i)));
        }
        return sb.append(")").toString();
    }

    private static String unit() {
        return GameManager.mode() == GameMode.DOM ? "점" : "킬";
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "분 " + (s % 60) + "초" : s + "초";
    }

    private static void send(MinecraftServer server, Component msg) {
        GameManager.broadcast(server, msg);
        MatchLog.event(msg.getString());
    }
}