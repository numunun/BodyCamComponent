package numunun.bodycamcomponent.game;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import java.util.HashSet;
import java.util.Set;
import numunun.bodycamcomponent.loadout.LoadoutManager;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.entity.CorpseEntity;
import numunun.bodycamcomponent.registry.ModEntities;
import numunun.bodycamcomponent.downed.DownedManager;
import numunun.bodycamcomponent.network.AbilitySyncPacket;
import numunun.bodycamcomponent.network.BodyCamNetwork;
import numunun.bodycamcomponent.network.IntroPacket;
import numunun.bodycamcomponent.network.OsdPacket;
import net.minecraftforge.network.PacketDistributor;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Random;
import javax.annotation.Nullable;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class GameManager {
    private static final UUID LEADER_HEALTH_ID = UUID.fromString("6f1f7a52-3c1e-4c55-9b7e-2b1a4c8e9d01");
    private static final int KILL_CREDIT_TICKS = 20 * 180;  // 마지막 공격 후 킬 인정 시간 (3분)
    private static final String OBJECTIVE = "bodycam";

    private static boolean running = false;
    private static GameMode mode = GameMode.TDM;
    private static int target = 30;
    private static int respawnDelayTicks = 100;
    private static boolean prevImmediateRespawn = false;

    private static final Map<String, Integer> teamScores = new HashMap<>();
    private static final Map<UUID, Integer> playerKills = new HashMap<>();
    private static final Map<UUID, Attack> lastAttack = new HashMap<>();
    private static final Map<UUID, DeathInfo> deathInfo = new HashMap<>();
    private static final Map<UUID, Waiting> waiting = new HashMap<>();

    private record Attack(UUID attacker, long time) {}
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

    private static boolean paused = false;
    private static int resumeTicks = 0;
    private static final Map<UUID, Vec3> frozenPos = new HashMap<>();
    private static final Set<UUID> pendingSpawn = new HashSet<>();

    private static final Map<UUID, Integer> playerDeaths = new HashMap<>();
    private static final Map<UUID, String> playerNames = new HashMap<>();
    private static final Map<UUID, String> playerTeams = new HashMap<>();
    private static boolean prevShowDeathMessages = true;
    private static String countdownLabel = "재개";
    private static boolean startPhase = false;  // 시작 카운트다운 중 (숫자 대신 암전 연출)
    private static final Map<UUID, String> playerRoles = new HashMap<>();
    private static final List<String> roleOrder = new ArrayList<>();  // 모든 팀이 공유하는 직급 순서
    private static final String[] CODE_A = {
            "오리온", "타이탄", "아틀라스", "하데스", "피닉스", "히드라", "아르테미스", "헬리오스",
            "사일런트", "아이언", "고스트", "크림슨", "프로즌", "노던", "할로우", "스틸"
    };
    private static final String[] CODE_B = {
            "스피어", "해머", "랜스", "블레이드", "팽", "서펀트", "이클립스",
            "스톰", "게이트", "베일", "앵커", "호라이즌", "레퀴엠", "바스티온"
    };

    private static final Map<UUID, Integer> playerRevives = new HashMap<>();
    private static long startedAtMs = 0;
    private static String codename = "";

    private GameManager() {}

    public static boolean isRunning() { return running; }
    public static boolean isFfa() {
        return running && mode == GameMode.FFA;
    }

    @Nullable
    public static String roleOf(ServerPlayer p) {
        return running ? playerRoles.get(p.getUUID()) : null;
    }

    // 빈사 상태 팀원을 살렸을 때 (DownedManager에서 호출)
    public static void recordRevive(ServerPlayer helper) {
        if (running) playerRevives.merge(helper.getUUID(), 1, Integer::sum);
    }

    // 팀장 판정: 팀전에서 직급 목록의 첫 번째 직급
    public static boolean isLeader(ServerPlayer p) {
        if (!mode.isTeamMode()) return false;
        String role = roleOf(p);
        return role != null && role.equals(roleList().get(0));
    }

    public static boolean isOperator(ServerPlayer p) {
        return BodyCamConfig.OPERATOR_ROLE.get().equals(roleOf(p));
    }

    public static boolean isRecon(ServerPlayer p) {
        return BodyCamConfig.RECON_ROLE.get().equals(roleOf(p));
    }

    private static void sendAbility(ServerPlayer p) {
        BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new AbilitySyncPacket(isOperator(p)));
    }

    // 팀장 추가 체력 적용 (스폰할 때마다)
    private static void applyLeaderHealth(ServerPlayer p) {
        AttributeInstance inst = p.getAttribute(Attributes.MAX_HEALTH);
        if (inst == null) return;
        inst.removeModifier(LEADER_HEALTH_ID);
        int bonus = BodyCamConfig.LEADER_HEALTH_BONUS.get();
        if (bonus > 0 && isLeader(p)) {
            inst.addTransientModifier(new AttributeModifier(LEADER_HEALTH_ID, "bodycam_leader_health",
                    bonus, AttributeModifier.Operation.ADDITION));
        }
    }

    // 경기 종료 시 원래 체력으로
    private static void clearLeaderHealth(ServerPlayer p) {
        AttributeInstance inst = p.getAttribute(Attributes.MAX_HEALTH);
        if (inst != null && inst.getModifier(LEADER_HEALTH_ID) != null) {
            inst.removeModifier(LEADER_HEALTH_ID);
            if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
        }
    }

    // 팀 표시 이름 (내부 이름 blue → 화면 이름 케스트렐)
    static String teamDisplay(MinecraftServer server, String teamName) {
        PlayerTeam t = server.getScoreboard().getPlayerTeam(teamName);
        return t != null ? t.getDisplayName().getString() : teamName.toUpperCase();
    }

    static int target() { return target; }

    static int leadingScore() {
        return teamScores.values().stream().max(Integer::compare).orElse(0);
    }

    static int addTeamScore(MinecraftServer server, String team, int amount) {
        int total = teamScores.merge(team, amount, Integer::sum);
        setSidebarScore(server, teamEntry(team), total);
        return total;
    }

    private static String unit() {
        return mode == GameMode.DOM ? "점" : "킬";
    }

    public static boolean isPaused() { return running && paused; }

    // ── 일시정지 ──
    public static boolean pause(MinecraftServer server, String reason) {
        if (!running || paused) return false;
        paused = true;
        resumeTicks = 0;
        frozenPos.clear();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isParticipant(p) && !waiting.containsKey(p.getUUID())) frozenPos.put(p.getUUID(), p.position());
        }
        sendOsd(OsdPacket.PAUSE, reason, 0);
        broadcast(server, Component.literal("[일시 정지] " + reason).withStyle(ChatFormatting.YELLOW));
        sendResumeButton(server);
        return true;
    }

    public static boolean resume(MinecraftServer server) {
        if (!running || !paused || resumeTicks > 0) return false;
        countdownLabel = "재개";
        startPhase = false;
        resumeTicks = 60;  // 3초 카운트다운
        sendOsd(OsdPacket.PLAY, "", 3);
        return true;
    }

    private static void finishResume(MinecraftServer server) {
        paused = false;
        frozenPos.clear();
        // 일시정지 중 들어온 플레이어 스폰
        for (UUID id : pendingSpawn) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p != null && isParticipant(p) && !waiting.containsKey(id)) {
                ensureRole(p);
                spawnParticipant(p);
                sendIntro(p, 0);
            }
        }
        pendingSpawn.clear();
        startPhase = false;
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

    private static void showTitle(MinecraftServer server, Component title, Component subtitle, int in, int stay, int out) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            p.connection.send(new ClientboundSetTitlesAnimationPacket(in, stay, out));
            p.connection.send(new ClientboundSetTitleTextPacket(title));
            p.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
        }
    }

    // ── 접속/퇴장 시 자동 일시정지 ──
    public static void onLogin(ServerPlayer p) {
        if (!running) return;
        pendingSpawn.add(p.getUUID());
        if (paused) {
            frozenPos.put(p.getUUID(), p.position());
            broadcast(p.server, Component.literal(p.getScoreboardName() + "님이 접속했습니다. 재개하면 스폰됩니다.")
                    .withStyle(ChatFormatting.YELLOW));
            BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                    new OsdPacket(OsdPacket.PAUSE, "경기 일시정지 중", 0));
        } else {
            pause(p.server, p.getScoreboardName() + "님이 접속했습니다.");
            frozenPos.put(p.getUUID(), p.position());
        }
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
        respawnDelayTicks = BodyCamConfig.RESPAWN_DELAY.get() * 20;
        teamScores.clear();
        playerKills.clear();
        playerDeaths.clear();
        playerNames.clear();
        playerTeams.clear();
        playerRoles.clear();
        playerRevives.clear();
        startedAtMs = System.currentTimeMillis();
        Random codeRnd = new Random();
        codename = CODE_A[codeRnd.nextInt(CODE_A.length)] + " " + CODE_B[codeRnd.nextInt(CODE_B.length)] + " 작전";
        lastAttack.clear();
        deathInfo.clear();
        waiting.clear();
        running = true;
        LoadoutManager.setEnabled(true);

        // 사망 화면 건너뛰기
        GameRules.BooleanValue rule = server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN);
        prevImmediateRespawn = rule.get();
        rule.set(true, server);

        // 사망 메시지: 바닐라 메시지는 끄고, 팀원에게만 직접 알림 (onDeath 참고)
        GameRules.BooleanValue deathMsg = server.getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES);
        prevShowDeathMessages = deathMsg.get();
        deathMsg.set(false, server);

        // 지난 경기 시체 정리
        for (ServerLevel level : server.getAllLevels()) {
            for (CorpseEntity corpse : level.getEntities(ModEntities.CORPSE.get(), c -> true)) {
                corpse.discard();
            }
        }

        if (mode == GameMode.DOM) DominationManager.start(server);
        setupSidebar(server);
        assignRoles(server);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isParticipant(p)) spawnParticipant(p);
        }
        broadcast(server, Component.literal("[작전 개시] " + mode.label).withStyle(ChatFormatting.GRAY));
        sendIntros(server, BodyCamConfig.START_COUNTDOWN.get());
        startCountdown(server, BodyCamConfig.START_COUNTDOWN.get());
        return null;
    }

    // 시작 전 제자리 고정 카운트다운 (일시정지 기능 재활용)
    private static void startCountdown(MinecraftServer server, int seconds) {
        if (seconds <= 0) return;
        paused = true;
        frozenPos.clear();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isParticipant(p) && !waiting.containsKey(p.getUUID())) frozenPos.put(p.getUUID(), p.position());
        }
        countdownLabel = "시작!";
        startPhase = true;
        resumeTicks = seconds * 20;
    }

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

    public static void reset(MinecraftServer server) {
        boolean wasRunning = running;
        running = false;
        BodyCamNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new AbilitySyncPacket(false));
        for (ServerPlayer p : server.getPlayerList().getPlayers()) clearLeaderHealth(p);
        DominationManager.stop();
        DownedManager.releaseAll(server);
        paused = false;
        resumeTicks = 0;
        startPhase = false;
        frozenPos.clear();
        pendingSpawn.clear();
        lastAttack.clear();
        deathInfo.clear();
        LoadoutManager.setEnabled(false);

        // 대기 중인 플레이어 원상 복구
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

        if (wasRunning) {
            server.getGameRules().getRule(GameRules.RULE_DO_IMMEDIATE_RESPAWN).set(prevImmediateRespawn, server);
            server.getGameRules().getRule(GameRules.RULE_SHOWDEATHMESSAGES).set(prevShowDeathMessages, server);
        }
    }

    // ── 참가자 판정 (리스폰 대기 중인 관전자도 참가자) ──
    public static boolean isParticipant(ServerPlayer p) {
        if (!running) return false;
        if (p.isSpectator() && !waiting.containsKey(p.getUUID())) return false;
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
        remember(p);
        List<ItemStack> attachments = LoadoutManager.clearTargets(p);
        List<ItemStack> guns = LoadoutManager.giveRandomLoadout(p);
        LoadoutManager.installOrGive(p, guns, attachments);
        LoadoutManager.autoInstallFromInventory(p, guns);
        LoadoutManager.giveRoleKit(p, playerRoles.get(p.getUUID()));
        applyLeaderHealth(p);
        heal(p);
        teleportToSpawn(p);
        sendAbility(p);
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

    // ── 리스폰: 대기(관전) 시작 ──
    public static void onRespawn(ServerPlayer p) {
        if (!isParticipant(p)) return;
        DeathInfo d = deathInfo.remove(p.getUUID());
        GameType prev = p.gameMode.getGameModeForPlayer();

        if (respawnDelayTicks <= 0) {
            finishWaiting(p, prev);
            return;
        }
        waiting.put(p.getUUID(), new Waiting(respawnDelayTicks, prev, d));
        p.setGameMode(GameType.SPECTATOR);
        if (d != null) anchor(p, d);
    }

    private static void finishWaiting(ServerPlayer p, GameType prev) {
        p.setCamera(p);
        if (p.isSpectator()) p.setGameMode(prev);
        List<ItemStack> guns = LoadoutManager.giveRandomLoadout(p);
        LoadoutManager.restoreAfterRespawn(p, guns);
        LoadoutManager.giveRoleKit(p, playerRoles.get(p.getUUID()));
        heal(p);
        teleportToSpawn(p);
    }

    public static void onLogout(ServerPlayer p) {
        Waiting w = waiting.remove(p.getUUID());
        if (w != null) {
            p.setCamera(p);
            p.setGameMode(w.prevMode);
        }
        if (!running) return;
        frozenPos.remove(p.getUUID());
        pendingSpawn.remove(p.getUUID());
        if (paused) {
            broadcast(p.server, Component.literal(p.getScoreboardName() + "님이 나갔습니다.").withStyle(ChatFormatting.YELLOW));
        } else {
            pause(p.server, p.getScoreboardName() + "님이 나갔습니다.");
        }
    }

    // ── 매 틱: 대기 시간 진행, 관전 대상 유지 ──
    public static void tick(MinecraftServer server) {
        if (!running) return;
        if (paused) {
            if (resumeTicks > 0) {
                if (--resumeTicks == 0) finishResume(server);
            }
            holdFrozen(server);
            return;  // 일시정지 중에는 리스폰 대기 시간도 멈춤
        }
        if (mode == GameMode.DOM) {
            DominationManager.tick(server);
            if (!running) return;  // 점령전 승리로 경기 종료
        }
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
        if (mode.isTeamMode()) {
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
        if (!o.isAlive() || waiting.containsKey(o.getUUID()) || !isParticipant(o)) return false;
        if (mode.isTeamMode()) {
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

        if (!isParticipant(victim)) return;
        remember(victim);
        playerDeaths.merge(victim.getUUID(), 1, Integer::sum);
        deathInfo.put(victim.getUUID(), new DeathInfo(victim.level().dimension(), victim.position(),
                killer != null && killer != victim ? killer.getUUID() : null));

        if (killer == null || killer == victim || !isParticipant(killer)) return;

        if (mode.isTeamMode()) {
            Team vt = victim.getTeam();
            Team kt = killer.getTeam();
            if (kt == null) return;
            if (vt != null && vt.getName().equals(kt.getName())) {
                return;  // 아군 사살: 점수 없음, 알림 없음
            }
            playerKills.merge(killer.getUUID(), 1, Integer::sum);
            if (mode == GameMode.DOM) return;  // 점령전: 킬은 결과 기록만
            int score = teamScores.merge(kt.getName(), 1, Integer::sum);
            setSidebarScore(server, teamEntry(kt.getName()), score);
            if (score >= target) end(server, Component.literal(teamDisplay(server, kt.getName()) + " 승리"));
        } else {
            int kills = playerKills.merge(killer.getUUID(), 1, Integer::sum);
            setSidebarScore(server, killer.getScoreboardName(), kills);
            if (kills >= target) end(server, Component.literal(killer.getScoreboardName() + " 승리"));
        }
    }

    // ── 경기 종료 ──
    static void end(MinecraftServer server, Component result) {
        reset(server);
        sendOsd(OsdPacket.STOP, result.getString(), 0);
        broadcast(server, Component.literal("[작전 종료] ").withStyle(ChatFormatting.GRAY).append(result));
        announceResults(server, result);
    }

    // ── 경기 결과 ──
    private static void remember(ServerPlayer p) {
        playerNames.put(p.getUUID(), p.getScoreboardName());
        Team t = p.getTeam();
        if (t != null) playerTeams.put(p.getUUID(), t.getName());
    }

    // ── 작전 보고서 ──
    private static void announceResults(MinecraftServer server, Component result) {
        if (playerNames.isEmpty()) return;

        List<UUID> ids = new ArrayList<>(playerNames.keySet());
        ids.sort((a, b) -> {
            int c = Integer.compare(mvpScore(b), mvpScore(a));
            return c != 0 ? c : Integer.compare(playerDeaths.getOrDefault(a, 0), playerDeaths.getOrDefault(b, 0));
        });

        List<String> teams = new ArrayList<>(new LinkedHashSet<>(playerTeams.values()));
        teams.sort((a, b) -> Integer.compare(teamScores.getOrDefault(b, 0), teamScores.getOrDefault(a, 0)));

        // 머리말
        broadcast(server, Component.literal("──────── 작전 보고서 ────────").withStyle(ChatFormatting.GRAY));
        broadcast(server, field("작전명", codename));
        broadcast(server, field("소속", BodyCamConfig.INTRO_UNIT_LINE.get()));
        broadcast(server, field("유형", mode.label + " · " + formatDuration(System.currentTimeMillis() - startedAtMs)));
        broadcast(server, field("결과", result.getString() + scoreSummary(teams)));
        broadcast(server, Component.literal("─────────────").withStyle(ChatFormatting.DARK_GRAY));

        // 부대별 전과
        if (mode.isTeamMode()) {
            for (String team : teams) {
                PlayerTeam pt = server.getScoreboard().getPlayerTeam(team);
                ChatFormatting color = pt != null ? pt.getColor() : ChatFormatting.WHITE;
                broadcast(server, Component.literal("■ " + teamDisplay(server, team) + "  "
                        + teamScores.getOrDefault(team, 0) + unit()).withStyle(color, ChatFormatting.BOLD));
                for (UUID id : ids) {
                    if (team.equals(playerTeams.get(id))) broadcast(server, resultLine("  ", id));
                }
            }
        } else {
            int rank = 1;
            for (UUID id : ids) broadcast(server, resultLine(" " + rank++ + ". ", id));
        }

        // 최우수 요원
        broadcast(server, Component.literal("─────────────").withStyle(ChatFormatting.DARK_GRAY));
        UUID mvp = ids.get(0);
        if (mvpScore(mvp) > 0) {
            String role = playerRoles.get(mvp);
            broadcast(server, Component.literal("최우수 요원  ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal((role != null ? "[" + role + "] " : "") + playerNames.get(mvp))
                            .withStyle(ChatFormatting.WHITE)));
        }
    }

    // 사살 1 = 10점, 구조 1 = 5점, 점령 기여 1초 = 1점
    private static int mvpScore(UUID id) {
        int score = playerKills.getOrDefault(id, 0) * 10 + playerRevives.getOrDefault(id, 0) * 5;
        if (mode == GameMode.DOM) score += DominationManager.captureSeconds(id);
        return score;
    }

    private static Component resultLine(String prefix, UUID id) {
        String role = playerRoles.get(id);
        StringBuilder sb = new StringBuilder(prefix);
        if (role != null) sb.append('[').append(role).append("] ");
        sb.append(playerNames.get(id))
                .append(" · 사살 ").append(playerKills.getOrDefault(id, 0))
                .append(" · 손실 ").append(playerDeaths.getOrDefault(id, 0));
        int revives = playerRevives.getOrDefault(id, 0);
        if (revives > 0) sb.append(" · 구조 ").append(revives);
        if (mode == GameMode.DOM) sb.append(" · 거점 ").append(formatDuration(DominationManager.captureSeconds(id) * 1000L));
        return Component.literal(sb.toString()).withStyle(ChatFormatting.WHITE);
    }

    private static Component field(String label, String value) {
        return Component.literal(label + "  ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(value).withStyle(ChatFormatting.GRAY));
    }

    // 팀전 점수 요약: (30 : 24)
    private static String scoreSummary(List<String> teams) {
        if (!mode.isTeamMode() || teams.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" (");
        for (int i = 0; i < teams.size(); i++) {
            if (i > 0) sb.append(" : ");
            sb.append(teamScores.getOrDefault(teams.get(i), 0));
        }
        return sb.append(")").toString();
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        return s >= 60 ? (s / 60) + "분 " + (s % 60) + "초" : s + "초";
    }

    // ── 점수판 ──
    private static String teamEntry(String teamName) {
        return teamName.toUpperCase();
    }

    private static void setupSidebar(MinecraftServer server) {
        // 사이드바는 쓰지 않음 (팀 상태는 클라이언트 HUD가 표시). 예전 점수판만 정리
        Scoreboard sb = server.getScoreboard();
        Objective old = sb.getObjective(OBJECTIVE);
        if (old != null) sb.removeObjective(old);
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
        sb.append("진행 중: ").append(running ? (paused ? "일시 정지" : "예") : "아니오").append('\n');
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

    static void broadcast(MinecraftServer server, Component msg) {
        server.getPlayerList().broadcastSystemMessage(msg, false);
    }

    // ── 직급 배정: 모든 팀이 같은 직급 구성을 받음 ──
    private static List<String> roleList() {
        List<String> roles = new ArrayList<>(BodyCamConfig.ROLE_TITLES.get());
        if (roles.isEmpty()) roles.add("대원");
        return roles;
    }

    private static void assignRoles(MinecraftServer server) {
        List<String> roles = roleList();
        Random rnd = new Random();

        // 첫 직급(팀장)은 맨 앞 고정, 나머지는 섞어서 모든 팀이 공유
        roleOrder.clear();
        roleOrder.add(roles.get(0));
        List<String> rest = new ArrayList<>(roles.subList(1, roles.size()));
        Collections.shuffle(rest, rnd);
        roleOrder.addAll(rest);

        Map<String, List<ServerPlayer>> byTeam = new LinkedHashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!isParticipant(p)) continue;
            String key = mode.isTeamMode() && p.getTeam() != null ? p.getTeam().getName() : "";
            byTeam.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }

        for (List<ServerPlayer> group : byTeam.values()) {
            List<ServerPlayer> members = new ArrayList<>(group);
            Collections.shuffle(members, rnd);  // 팀 안에서 누가 어떤 직급을 받을지만 랜덤
            for (int i = 0; i < members.size(); i++) {
                String role = mode.isTeamMode() ? roleAt(i) : roles.get(rnd.nextInt(roles.size()));
                playerRoles.put(members.get(i).getUUID(), role);
            }
        }
    }

    // 인원이 직급 수보다 많으면 첫 직급을 제외하고 다시 순환
    private static String roleAt(int index) {
        if (index < roleOrder.size()) return roleOrder.get(index);
        if (roleOrder.size() <= 1) return roleOrder.get(0);
        return roleOrder.get(1 + (index - 1) % (roleOrder.size() - 1));
    }

    // 경기 중 들어온 사람: 자기 팀의 다음 순번 직급
    private static void ensureRole(ServerPlayer p) {
        if (playerRoles.containsKey(p.getUUID())) return;  // 재접속: 원래 직급 유지

        if (!mode.isTeamMode() || p.getTeam() == null || roleOrder.isEmpty()) {
            List<String> roles = roleList();
            playerRoles.put(p.getUUID(), roles.get(new Random().nextInt(roles.size())));
            return;
        }
        String team = p.getTeam().getName();
        int count = 0;
        for (UUID id : playerRoles.keySet()) {
            if (team.equals(playerTeams.get(id))) count++;
        }
        playerRoles.put(p.getUUID(), roleAt(count));
    }

    // ── 시작 연출 전송 ──
    private static void sendIntros(MinecraftServer server, int blackSeconds) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (isParticipant(p)) sendIntro(p, blackSeconds);
        }
    }

    private static void sendIntro(ServerPlayer p, int blackSeconds) {
        String teamLine;
        int color = 0xFFFFFF;
        Team t = p.getTeam();
        if (mode.isTeamMode() && t != null) {
            teamLine = BodyCamConfig.INTRO_TEAM_FORMAT.get().replace("{team}", teamDisplay(p.server, t.getName()));
            PlayerTeam pt = p.server.getScoreboard().getPlayerTeam(t.getName());
            Integer c = pt != null ? pt.getColor().getColor() : null;
            if (c != null) color = c;
        } else {
            teamLine = BodyCamConfig.INTRO_FFA_TEXT.get();
        }
        String role = playerRoles.getOrDefault(p.getUUID(), "");
        BodyCamNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> p),
                new IntroPacket(BodyCamConfig.INTRO_UNIT_LINE.get(), teamLine, color, role,
                        p.getScoreboardName(), blackSeconds * 20));
    }

    private static void sendOsd(int kind, String sub, int seconds) {
        BodyCamNetwork.CHANNEL.send(PacketDistributor.ALL.noArg(), new OsdPacket(kind, sub, seconds));
    }

    // 팀원에게만 짧게 사망 알림 (누가 쐈는지는 숨김)
//    private static void notifyTeam(ServerPlayer victim) {
//        Team t = victim.getTeam();
//        if (t == null) return;
//        Component msg = Component.literal("✝ " + victim.getScoreboardName() + " 사망").withStyle(ChatFormatting.GRAY);
//        for (ServerPlayer p : victim.server.getPlayerList().getPlayers()) {
//            Team pt = p.getTeam();
//            if (p != victim && pt != null && pt.getName().equals(t.getName())) p.sendSystemMessage(msg);
//        }
//    }
}