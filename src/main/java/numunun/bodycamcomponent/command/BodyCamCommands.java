package numunun.bodycamcomponent.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
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

                .then(Commands.literal("team").then(Commands.literal("setup").executes(ctx -> {
                    Scoreboard sb = ctx.getSource().getServer().getScoreboard();
                    setupTeam(sb, "blue", ChatFormatting.BLUE);
                    setupTeam(sb, "red", ChatFormatting.RED);
                    return ok(ctx, "blue, red 팀을 설정했습니다. 배정: /team join <팀> <플레이어>");
                })))

                .then(Commands.literal("mode")
                        .then(Commands.literal("tdm").executes(ctx -> setMode(ctx, GameMode.TDM)))
                        .then(Commands.literal("ffa").executes(ctx -> setMode(ctx, GameMode.FFA))))

                .then(Commands.literal("target").then(Commands.argument("kills", IntegerArgumentType.integer(1, 1000)).executes(ctx -> {
                    if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
                    int kills = IntegerArgumentType.getInteger(ctx, "kills");
                    GameSettings s = GameSettings.get(ctx.getSource().getServer());
                    s.targetKills = kills;
                    s.setDirty();
                    return ok(ctx, "목표 킬 수: " + kills);
                })))

                .then(Commands.literal("start").executes(ctx -> {
                    String err = GameManager.start(ctx.getSource().getServer());
                    return err == null ? 1 : fail(ctx, err);
                }))
                .then(Commands.literal("stop").executes(ctx ->
                        GameManager.stop(ctx.getSource().getServer()) ? 1 : fail(ctx, "진행 중인 경기가 없습니다.")))
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
                            return ok(ctx, "랜덤 장비를 지급했습니다.");
                        })))
        );
    }

    private static int setMode(CommandContext<CommandSourceStack> ctx, GameMode mode) {
        if (GameManager.isRunning()) return fail(ctx, "경기 중에는 변경할 수 없습니다.");
        GameSettings s = GameSettings.get(ctx.getSource().getServer());
        s.mode = mode;
        s.setDirty();
        return ok(ctx, "모드: " + mode.label);
    }

    private static void setupTeam(Scoreboard sb, String name, ChatFormatting color) {
        PlayerTeam t = sb.getPlayerTeam(name);
        if (t == null) t = sb.addPlayerTeam(name);
        t.setColor(color);
        t.setDisplayName(Component.literal(name.toUpperCase()).withStyle(color));
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