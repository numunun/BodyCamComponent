package numunun.bodycamcomponent.game;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

// 월드에 저장되는 경기 설정
public class GameSettings extends SavedData {
    private static final String NAME = "bodycam_settings";
    public record CapturePoint(String name, ResourceKey<Level> dim, BlockPos center) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putString("name", name);
            t.putString("dim", dim.location().toString());
            t.putLong("center", center.asLong());
            return t;
        }

        static CapturePoint load(CompoundTag t) {
            return new CapturePoint(t.getString("name"),
                    ResourceKey.create(Registries.DIMENSION, new ResourceLocation(t.getString("dim"))),
                    BlockPos.of(t.getLong("center")));
        }
    }

    public GameMode mode = GameMode.TDM;
    public int targetKills = 30;
    @Nullable public Region field;
    public final Map<String, Region> teamSpawns = new HashMap<>();
    public int domTargetScore = 150;
    public final List<CapturePoint> points = new ArrayList<>();

    public static GameSettings get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(GameSettings::load, GameSettings::new, NAME);
    }

    private static GameSettings load(CompoundTag tag) {
        GameSettings s = new GameSettings();
        s.mode = GameMode.byId(tag.getString("mode"));
        if (tag.contains("target")) s.targetKills = tag.getInt("target");
        if (tag.contains("field")) s.field = Region.load(tag.getCompound("field"));
        CompoundTag spawns = tag.getCompound("spawns");
        for (String key : spawns.getAllKeys()) {
            s.teamSpawns.put(key, Region.load(spawns.getCompound(key)));
        }
        if (tag.contains("domTarget")) s.domTargetScore = tag.getInt("domTarget");
        ListTag pts = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < pts.size(); i++) s.points.add(CapturePoint.load(pts.getCompound(i)));
        return s;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putString("mode", mode.id);
        tag.putInt("target", targetKills);
        if (field != null) tag.put("field", field.save());
        CompoundTag spawns = new CompoundTag();
        teamSpawns.forEach((k, v) -> spawns.put(k, v.save()));
        tag.put("spawns", spawns);
        tag.putInt("domTarget", domTargetScore);
        ListTag pts = new ListTag();
        for (CapturePoint pt : points) pts.add(pt.save());
        tag.put("points", pts);
        return tag;
    }
}