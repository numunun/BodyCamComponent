package numunun.bodycamcomponent.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.TeamArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import numunun.bodycamcomponent.BodyCamComponent;
import numunun.bodycamcomponent.game.GameManager;
import numunun.bodycamcomponent.game.GameMode;
import numunun.bodycamcomponent.game.GameSettings;
import numunun.bodycamcomponent.game.Region;
import numunun.bodycamcomponent.game.Selections;
import numunun.bodycamcomponent.loadout.LoadoutManager;
import numunun.bodycamcomponent.registry.ModItems;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.game.TeamBalancer;

import java.util.List;

@Mod.EventBusSubscriber(modid = BodyCamComponent.MODID)
public final class BodyCamCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("bodycam")
                .requires(src -> src.hasPermission(2))

                .then(Commands.literal("wand").executes(ctx -> {
                    ServerPlayer p = ctx.getSource().getPlayerOrException();
                    p.getInventory().add(new ItemStack(ModItems.REGION_WAND.get()));
                    return ok(ctx, "구역 지정 도구를 지급했습니다. 좌클릭: 지점 1, 우클릭: 지점 2");
                }))

                .then(Commands.literal("field").then(Commands.literal("set").executes(ctx -> {
                    if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
                    Region r = Selections.get(ctx.getSource().getPlayerOrException());
                    if (r == null) return fail(ctx, "도구로 두 지점을 먼저 지정하세요.");
                    GameSettings s = GameSettings.get(ctx.getSource().getServer());
                    s.field = r;
                    s.setDirty();
                    return ok(ctx, "개인전 필드 지정: " + r.describe());
                })))

                .then(Commands.literal("spawn")
                        .then(Commands.literal("set").then(Commands.argument("team", TeamArgument.team()).executes(ctx -> {
                            if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
                            PlayerTeam team = TeamArgument.getTeam(ctx, "team");
                            Region r = Selections.get(ctx.getSource().getPlayerOrException());
                            if (r == null) return fail(ctx, "도구로 두 지점을 먼저 지정하세요.");
                            GameSettings s = GameSettings.get(ctx.getSource().getServer());
                            s.teamSpawns.put(team.getName(), r);
                            s.setDirty();
                            return ok(ctx, "'" + team.getName() + "' 팀 스폰 범위 지정: " + r.describe());
                        })))
                        .then(Commands.literal("clear").then(Commands.argument("team", TeamArgument.team()).executes(ctx -> {
                            if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
                            PlayerTeam team = TeamArgument.getTeam(ctx, "team");
                            GameSettings s = GameSettings.get(ctx.getSource().getServer());
                            if (s.teamSpawns.remove(team.getName()) == null) return fail(ctx, "지정된 스폰 범위가 없습니다.");
                            s.setDirty();
                            return ok(ctx, "'" + team.getName() + "' 팀 스폰 범위를 삭제했습니다.");
                        }))))

                .then(Commands.literal("team")
                        .then(Commands.literal("setup").executes(ctx -> {
                            Scoreboard sb = ctx.getSource().getServer().getScoreboard();
                            setupTeam(sb, "blue", "케스트렐", ChatFormatting.BLUE);
                            setupTeam(sb, "red", "볼코프", ChatFormatting.RED);
                            return ok(ctx, "케스트렐(blue), 볼코프(red) 팀을 설정했습니다. 배정: /teams 또는 /bodycam team auto");
                        }))
                        .then(Commands.literal("auto").executes(ctx -> {
                            String err = TeamBalancer.autoApply(ctx.getSource().getServer());
                            return err == null ? 1 : fail(ctx, err);
                        }))
                        .then(Commands.literal("menu").executes(ctx -> {
                            TeamBalancer.open(ctx.getSource().getPlayerOrException());
                            return 1;
                        })))

                .then(Commands.literal("mode")
                        .then(Commands.literal("tdm").executes(ctx -> setMode(ctx, GameMode.TDM)))
                        .then(Commands.literal("ffa").executes(ctx -> setMode(ctx, GameMode.FFA)))
                        .then(Commands.literal("dom").executes(ctx -> setMode(ctx, GameMode.DOM))))

                .then(Commands.literal("target").then(Commands.argument("kills", IntegerArgumentType.integer(1, 1000)).executes(ctx -> {
                    if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
                    int kills = IntegerArgumentType.getInteger(ctx, "kills");
                    GameSettings s = GameSettings.get(ctx.getSource().getServer());
                    s.targetKills = kills;
                    s.setDirty();
                    return ok(ctx, "목표 킬 수: " + kills);
                })))

                .then(Commands.literal("respawn").then(Commands.argument("seconds", IntegerArgumentType.integer(0, 300)).executes(ctx -> {
                    int sec = IntegerArgumentType.getInteger(ctx, "seconds");
                    BodyCamConfig.RESPAWN_DELAY.set(sec);
                    BodyCamConfig.RESPAWN_DELAY.save();
                    return ok(ctx, "리스폰 대기 시간: " + sec + "초 (다음 경기부터 적용)");
                })))

                .then(Commands.literal("point")
                        .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            String name = StringArgumentType.getString(ctx, "name").toUpperCase();
                            GameSettings s = GameSettings.get(ctx.getSource().getServer());
                            if (s.points.size() >= 5) return fail(ctx, "거점은 최대 5개까지 만들 수 있습니다.");
                            if (s.points.stream().anyMatch(pt -> pt.name().equals(name))) return fail(ctx, "이미 있는 거점 이름입니다.");
                            BlockPos center = p.blockPosition().below();
                            s.points.add(new GameSettings.CapturePoint(name, p.level().dimension(), center));
                            s.setDirty();
                            return ok(ctx, "거점 " + name + " 추가: (" + center.getX() + ", " + center.getY() + ", " + center.getZ() + ")");
                        })))
                        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                            if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
                            String name = StringArgumentType.getString(ctx, "name").toUpperCase();
                            GameSettings s = GameSettings.get(ctx.getSource().getServer());
                            if (!s.points.removeIf(pt -> pt.name().equals(name))) return fail(ctx, "그런 거점이 없습니다.");
                            s.setDirty();
                            return ok(ctx, "거점 " + name + " 삭제");
                        })))
                        .then(Commands.literal("list").executes(ctx -> {
                            GameSettings s = GameSettings.get(ctx.getSource().getServer());
                            if (s.points.isEmpty()) return ok(ctx, "거점이 없습니다.");
                            StringBuilder sb = new StringBuilder("거점 목록:");
                            for (GameSettings.CapturePoint pt : s.points) {
                                sb.append("\n  ").append(pt.name()).append(": (").append(pt.center().getX()).append(", ")
                                        .append(pt.center().getY()).append(", ").append(pt.center().getZ()).append(")");
                            }
                            return ok(ctx, sb.toString());
                        })))
                .then(Commands.literal("domtarget").then(Commands.argument("score", IntegerArgumentType.integer(1, 10000)).executes(ctx -> {
                    if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
                    int score = IntegerArgumentType.getInteger(ctx, "score");
                    GameSettings s = GameSettings.get(ctx.getSource().getServer());
                    s.domTargetScore = score;
                    s.setDirty();
                    return ok(ctx, "점령전 목표 점수: " + score);
                })))

                .then(Commands.literal("start").executes(ctx -> {
                    String err = GameManager.start(ctx.getSource().getServer());
                    return err == null ? 1 : fail(ctx, err);
                }))
                .then(Commands.literal("stop").executes(ctx ->
                        GameManager.stop(ctx.getSource().getServer()) ? 1 : fail(ctx, "진행 중인 경기가 없습니다.")))
                .then(Commands.literal("end")
                        .executes(ctx -> GameManager.forceEnd(ctx.getSource().getServer(), null)
                                ? 1 : fail(ctx, "진행 중인 경기가 없습니다."))
                        .then(Commands.argument("winner", TeamArgument.team()).executes(ctx ->
                                GameManager.forceEnd(ctx.getSource().getServer(), TeamArgument.getTeam(ctx, "winner").getName())
                                        ? 1 : fail(ctx, "진행 중인 경기가 없습니다."))))
                .then(Commands.literal("pause").executes(ctx ->
                        GameManager.pause(ctx.getSource().getServer(), "관리자가 경기를 일시정지했습니다.")
                                ? 1 : fail(ctx, "진행 중인 경기가 없거나 이미 일시정지 상태입니다.")))
                .then(Commands.literal("resume").executes(ctx ->
                        GameManager.resume(ctx.getSource().getServer())
                                ? ok(ctx, "3초 뒤 경기를 재개합니다.") : fail(ctx, "일시정지 상태가 아닙니다.")))
                .then(Commands.literal("status").executes(ctx ->
                        ok(ctx, GameManager.status(ctx.getSource().getServer()))))

                .then(Commands.literal("loadout")
                        .then(Commands.literal("on").executes(ctx -> {
                            LoadoutManager.setEnabled(true);
                            return ok(ctx, "리스폰 시 랜덤 지급: 켜짐");
                        }))
                        .then(Commands.literal("off").executes(ctx -> {
                            LoadoutManager.setEnabled(false);
                            return ok(ctx, "리스폰 시 랜덤 지급: 꺼짐");
                        }))
                        .then(Commands.literal("give").executes(ctx -> {
                            ServerPlayer p = ctx.getSource().getPlayerOrException();
                            List<ItemStack> attachments = LoadoutManager.clearTargets(p);
                            List<ItemStack> guns = LoadoutManager.giveRandomLoadout(p);
                            LoadoutManager.installOrGive(p, guns, attachments);
                            LoadoutManager.autoInstallFromInventory(p, guns);
                            LoadoutManager.refreshHeldGun(p);
                            return ok(ctx, "랜덤 장비를 지급했습니다.");
                        })))
        );
        e.getDispatcher().register(Commands.literal("teams").executes(ctx -> {
            TeamBalancer.open(ctx.getSource().getPlayerOrException());
            return 1;
        }));
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, GameMode mode) {
        if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
        GameSettings s = GameSettings.get(ctx.getSource().getServer());
        s.mode = mode;
        s.setDirty();
        return ok(ctx, "모드: " + mode.label);
    }

    private static void setupTeam(Scoreboard sb, String id, String displayName, ChatFormatting color) {
        PlayerTeam t = sb.getPlayerTeam(id);
        if (t == null) t = sb.addPlayerTeam(id);
        t.setColor(color);
        t.setDisplayName(Component.literal(displayName).withStyle(color));
        t.setAllowFriendlyFire(true);
        t.setNameTagVisibility(Team.Visibility.NEVER);
    }

    private static int ok(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendSuccess(() -> Component.literal(msg), true);
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal(msg));
        return 0;
    }
}