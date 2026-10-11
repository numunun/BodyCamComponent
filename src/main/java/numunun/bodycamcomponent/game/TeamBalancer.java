package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraftforge.network.PacketDistributor;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.TeamActionPacket;
import numunun.bodycamcomponent.network.TeamScreenPacket;
import numunun.bodycamcomponent.util.MatchLog;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

// 전투력 기반 팀 자동 배정 + 조정 화면 (보기는 누구나, 수정은 관리자만)
public final class TeamBalancer {
    public static final int LEFT = 0;
    public static final int RIGHT = 1;
    public static final int BENCH = 2;   // 이번 경기 제외

    private static final int ATTEMPTS = 300;
    private static final Random RANDOM = new Random();

    private static final Map<UUID, Integer> draft = new LinkedHashMap<>();  // 확정 전 배정안
    private static final Set<UUID> viewers = new HashSet<>();               // 화면을 열어둔 사람

    private TeamBalancer() {}

    // 팀 두 개: 이름순 (blue 왼쪽, red 오른쪽)
    private static List<PlayerTeam> teams(MinecraftServer server) {
        List<PlayerTeam> list = new ArrayList<>(server.getScoreboard().getPlayerTeams());
        list.sort(Comparator.comparing(PlayerTeam::getName));
        return list.size() >= 2 ? list.subList(0, 2) : List.of();
    }

    // ── 화면 요청 처리 ──
    public static void handle(ServerPlayer p, int action, UUID target) {
        MinecraftServer server = p.server;
        if (action == TeamActionPacket.CLOSE) {
            viewers.remove(p.getUUID());
            return;
        }
        if (action == TeamActionPacket.OPEN) {
            open(p);
            return;
        }
        if (!canEdit(p)) return;  // 수정은 관리자만, 경기 중엔 불가

        syncDraft(server);
        switch (action) {
            case TeamActionPacket.MOVE -> {
                Integer side = draft.get(target);
                if (side != null) draft.put(target, (side + 1) % 3);  // 왼쪽 → 오른쪽 → 제외 → 왼쪽
            }
            case TeamActionPacket.AUTO -> balance(server, false);
            case TeamActionPacket.SHUFFLE -> balance(server, true);
            case TeamActionPacket.CONFIRM -> {
                String err = apply(server);
                if (err != null) p.sendSystemMessage(Component.literal(err).withStyle(ChatFormatting.RED));
            }
            default -> { }
        }
        sendToViewers(server);
    }

    public static void open(ServerPlayer p) {
        if (teams(p.server).isEmpty()) {
            p.sendSystemMessage(Component.literal("팀이 없습니다. 관리자가 /bodycam team setup 을 먼저 해야 합니다.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        viewers.add(p.getUUID());
        syncDraft(p.server);
        send(p, true);
    }

    private static boolean canEdit(ServerPlayer p) {
        return p.hasPermissions(2) && !GameManager.isRunning();
    }

    // ── 명령어, 경기 시작 시 자동 배정 ──
    @Nullable
    public static String autoApply(MinecraftServer server) {
        if (teams(server).isEmpty()) return "팀이 없습니다. /bodycam team setup 을 먼저 하세요.";
        syncDraft(server);
        balance(server, false);
        String err = apply(server);
        sendToViewers(server);
        return err;
    }

    // ── 배정안 관리 ──
    // 접속한 사람만 남기고, 새로 들어온 사람은 지금 팀 또는 합계가 낮은 쪽에 넣음
    private static void syncDraft(MinecraftServer server) {
        List<PlayerTeam> teams = teams(server);
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) online.add(p.getUUID());
        draft.keySet().removeIf(id -> !online.contains(id));

        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (draft.containsKey(p.getUUID())) continue;
            int side;
            if (!teams.isEmpty() && p.getTeam() != null && p.getTeam().getName().equals(teams.get(0).getName())) side = LEFT;
            else if (!teams.isEmpty() && p.getTeam() != null && p.getTeam().getName().equals(teams.get(1).getName())) side = RIGHT;
            else side = total(server, LEFT) <= total(server, RIGHT) ? LEFT : RIGHT;
            draft.put(p.getUUID(), side);
        }
    }

    private static int total(MinecraftServer server, int side) {
        int sum = 0;
        for (Map.Entry<UUID, Integer> e : draft.entrySet()) {
            if (e.getValue() == side) sum += RatingManager.rating(server, e.getKey());
        }
        return sum;
    }

    // 제외하지 않은 사람을 두 팀으로: 인원은 최대한 같게, 전투력 합계 차이는 최소로.
    // 섞기는 차이가 거의 같은 좋은 조합들 중에서 하나를 무작위로 고름
    private static void balance(MinecraftServer server, boolean shuffle) {
        List<UUID> pool = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : draft.entrySet()) {
            if (e.getValue() != BENCH) pool.add(e.getKey());
        }
        if (pool.isEmpty()) return;
        Map<UUID, Integer> rating = new HashMap<>();
        for (UUID id : pool) rating.put(id, RatingManager.rating(server, id));

        int n = pool.size();
        int leftSize = (n + 1) / 2;
        List<List<UUID>> candidates = new ArrayList<>();
        List<Integer> diffs = new ArrayList<>();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            List<UUID> order = new ArrayList<>(pool);
            Collections.shuffle(order, RANDOM);
            List<UUID> left = new ArrayList<>(order.subList(0, leftSize));
            List<UUID> right = new ArrayList<>(order.subList(leftSize, n));
            improveBySwaps(left, right, rating);
            candidates.add(left);
            diffs.add(Math.abs(sum(left, rating) - sum(right, rating)));
        }

        int best = Collections.min(diffs);
        List<UUID> chosen;
        if (shuffle) {
            int total = sum(pool, rating);
            int tolerance = Math.max(2, total / 20);  // 합계의 5% 이내면 비슷한 조합으로 봄
            List<List<UUID>> good = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                if (diffs.get(i) <= best + tolerance) good.add(candidates.get(i));
            }
            chosen = good.get(RANDOM.nextInt(good.size()));
        } else {
            chosen = candidates.get(diffs.indexOf(best));
        }

        Set<UUID> leftSet = new HashSet<>(chosen);
        for (UUID id : pool) draft.put(id, leftSet.contains(id) ? LEFT : RIGHT);
    }

    // 두 팀에서 한 명씩 바꿔서 차이가 줄어들면 계속 바꿈
    private static void improveBySwaps(List<UUID> left, List<UUID> right, Map<UUID, Integer> rating) {
        boolean improved = true;
        while (improved) {
            improved = false;
            int diff = sum(left, rating) - sum(right, rating);
            for (int i = 0; i < left.size() && !improved; i++) {
                for (int j = 0; j < right.size() && !improved; j++) {
                    int delta = rating.get(left.get(i)) - rating.get(right.get(j));
                    if (Math.abs(diff - 2 * delta) < Math.abs(diff)) {
                        UUID a = left.get(i);
                        left.set(i, right.get(j));
                        right.set(j, a);
                        improved = true;
                    }
                }
            }
        }
    }

    private static int sum(List<UUID> ids, Map<UUID, Integer> rating) {
        int s = 0;
        for (UUID id : ids) s += rating.get(id);
        return s;
    }

    // ── 확정: 실제 팀에 반영 ──
    @Nullable
    private static String apply(MinecraftServer server) {
        if (GameManager.isRunning()) return "경기 중에는 팀을 바꿀 수 없습니다.";
        List<PlayerTeam> teams = teams(server);
        if (teams.isEmpty()) return "팀이 없습니다. /bodycam team setup 을 먼저 하세요.";
        Scoreboard sb = server.getScoreboard();

        for (Map.Entry<UUID, Integer> e : draft.entrySet()) {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            if (p == null) continue;
            String name = p.getScoreboardName();
            if (e.getValue() == BENCH) {
                if (sb.getPlayersTeam(name) != null) sb.removePlayerFromTeam(name);
            } else {
                sb.addPlayerToTeam(name, teams.get(e.getValue()));
            }
        }

        String summary = summary(server, teams);
        GameManager.broadcast(server, Component.literal("[팀 배정] " + summary).withStyle(ChatFormatting.GRAY));
        MatchLog.event("팀 배정: " + summary);
        return null;
    }

    private static String summary(MinecraftServer server, List<PlayerTeam> teams) {
        StringBuilder sb = new StringBuilder();
        for (int side = LEFT; side <= RIGHT; side++) {
            if (side == RIGHT) sb.append("  /  ");
            sb.append(GameManager.teamDisplay(server, teams.get(side).getName()))
                    .append(" (합계 ").append(total(server, side)).append("): ");
            List<String> names = new ArrayList<>();
            for (Map.Entry<UUID, Integer> e : draft.entrySet()) {
                if (e.getValue() != side) continue;
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) names.add(p.getScoreboardName());
            }
            sb.append(String.join(", ", names));
        }
        return sb.toString();
    }

    // ── 화면으로 보내기 ──
    private static void sendToViewers(MinecraftServer server) {
        viewers.removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (UUID id : viewers) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null) send(p, false);
        }
    }

    private static void send(ServerPlayer p, boolean open) {
        MinecraftServer server = p.server;
        List<PlayerTeam> teams = teams(server);
        if (teams.isEmpty()) return;

        List<TeamScreenPacket.Entry> list = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : draft.entrySet()) {
            ServerPlayer o = server.getPlayerList().getPlayer(e.getKey());
            if (o == null) continue;
            list.add(new TeamScreenPacket.Entry(e.getKey(), o.getScoreboardName(),
                    RatingManager.rating(server, e.getKey()), e.getValue()));
        }
        list.sort(Comparator.comparingInt(TeamScreenPacket.Entry::rating).reversed());

        BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new TeamScreenPacket(
                GameManager.teamDisplay(server, teams.get(0).getName()), color(teams.get(0)),
                GameManager.teamDisplay(server, teams.get(1).getName()), color(teams.get(1)),
                canEdit(p), open, list));
    }

    private static int color(PlayerTeam t) {
        Integer c = t.getColor().getColor();
        return c != null ? c : 0xFFFFFF;
    }
}