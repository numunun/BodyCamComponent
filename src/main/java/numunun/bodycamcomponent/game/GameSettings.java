package numunun.bodycamcomponent.game;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

// 월드에 저장되는 경기 설정
public class GameSettings extends SavedData {
    private static final String NAME = "bodycam_settings";

    public GameMode mode = GameMode.TDM;
    public int targetKills = 30;
    @Nullable public Region field;
    public final Map<String, Region> teamSpawns = new HashMap<>();

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
        return tag;
    }
}