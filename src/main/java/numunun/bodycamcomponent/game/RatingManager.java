package numunun.bodycamcomponent.game;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import numunun.bodycamcomponent.util.MatchLog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 플레이어별 전투력 점수 (경기마다 누적, 서버를 꺼도 유지)
public final class RatingManager extends SavedData {
    private static final String NAME = "bodycam_ratings";
    static final double DEFAULT = 50.0;   // 처음 하는 사람
    private static final double ALPHA = 0.3;      // 최근 경기 반영 비율 (클수록 최근 경기가 크게 반영)
    private static final double MIN_MINUTES = 3.0;

    private static final class Entry {
        String name = "?";
        double rating = DEFAULT;
        int matches = 0;
    }

    private final Map<UUID, Entry> entries = new HashMap<>();

    static RatingManager get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(RatingManager::load, RatingManager::new, NAME);
    }

    public static int rating(MinecraftServer server, UUID id) {
        Entry e = get(server).entries.get(id);
        return (int) Math.round(e != null ? e.rating : DEFAULT);
    }

    // ── 경기가 끝날 때 반영 ──
    // 기여 = 사살 10 + 구조 5 + 점령 1초당 1 - 아군 사살 15 (10분 기준으로 환산)
    // 승패 = 승리 +20, 무승부 +10, 패배 0
    static void recordMatch(MinecraftServer server) {
        RatingManager data = get(server);
        GameMode mode = GameManager.mode();
        double minutes = Math.max(MIN_MINUTES, ReportManager.durationMs() / 60000.0);

        String winTeam = null;
        boolean draw = false;
        UUID ffaWinner = null;
        if (mode.isTeamMode()) {
            List<String> teams = new ArrayList<>(new LinkedHashSet<>(ScoreManager.teams()));
            teams.sort(Comparator.comparingInt(ScoreManager::teamScore).reversed());
            if (teams.size() >= 2 && ScoreManager.teamScore(teams.get(0)) == ScoreManager.teamScore(teams.get(1))) {
                draw = true;
            } else if (!teams.isEmpty()) {
                winTeam = teams.get(0);
            }
        } else {
            ffaWinner = ScoreManager.players().stream()
                    .max(Comparator.comparingInt(ScoreManager::kills)).orElse(null);
        }

        for (UUID id : ScoreManager.players()) {
            double contrib = ScoreManager.kills(id) * 10 + ScoreManager.revives(id) * 5
                    - ScoreManager.teamKills(id) * 15;
            if (mode == GameMode.DOM) contrib += DominationManager.captureSeconds(id);
            double perf = contrib / minutes * 10;

            double result;
            if (mode.isTeamMode()) result = draw ? 10 : (winTeam != null && winTeam.equals(ScoreManager.teamOf(id)) ? 20 : 0);
            else result = id.equals(ffaWinner) ? 20 : 0;

            double score = Math.max(0, perf + result);
            Entry e = data.entries.computeIfAbsent(id, k -> new Entry());
            e.name = ScoreManager.nameOf(id);
            double before = e.rating;
            e.rating = e.matches == 0 ? (DEFAULT + score) / 2 : e.rating * (1 - ALPHA) + score * ALPHA;
            e.matches++;
            MatchLog.event(String.format("전투력: %s %.0f → %.0f", e.name, before, e.rating));
        }
        data.setDirty();
    }

    // ── 저장 ──
    private static RatingManager load(CompoundTag tag) {
        RatingManager data = new RatingManager();
        ListTag list = tag.getList("players", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Entry e = new Entry();
            e.name = t.getString("name");
            e.rating = t.getDouble("rating");
            e.matches = t.getInt("matches");
            data.entries.put(t.getUUID("id"), e);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> en : entries.entrySet()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", en.getKey());
            t.putString("name", en.getValue().name);
            t.putDouble("rating", en.getValue().rating);
            t.putInt("matches", en.getValue().matches);
            list.add(t);
        }
        tag.put("players", list);
        return tag;
    }
}