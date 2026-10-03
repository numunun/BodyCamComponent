package numunun.bodycamcomponent.game;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public record Region(ResourceKey<Level> dimension, BlockPos min, BlockPos max) {
    private static final int SPAWN_ATTEMPTS = 64;

    public static Region of(ResourceKey<Level> dim, BlockPos a, BlockPos b) {
        return new Region(dim,
                new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ())),
                new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())));
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dimension.location().toString());
        t.putLong("min", min.asLong());
        t.putLong("max", max.asLong());
        return t;
    }

    public static Region load(CompoundTag t) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(t.getString("dim")));
        return new Region(dim, BlockPos.of(t.getLong("min")), BlockPos.of(t.getLong("max")));
    }

    public String describe() {
        return String.format("(%d, %d, %d) ~ (%d, %d, %d)",
                min.getX(), min.getY(), min.getZ(), max.getX(), max.getY(), max.getZ());
    }

    // ── 구역 안의 안전한 스폰 위치 (발 위치) ──
    @Nullable
    public BlockPos findSafeSpawn(ServerLevel level, RandomSource r) {
        for (int attempt = 0; attempt < SPAWN_ATTEMPTS; attempt++) {
            int x = Mth.randomBetweenInclusive(r, min.getX(), max.getX());
            int z = Mth.randomBetweenInclusive(r, min.getZ(), max.getZ());
            for (int y = max.getY() + 1; y > min.getY(); y--) {
                BlockPos feet = new BlockPos(x, y, z);
                if (isSafe(level, feet)) return feet;
            }
        }
        return null;
    }

    private static boolean isSafe(ServerLevel level, BlockPos feet) {
        BlockPos below = feet.below();
        BlockState floor = level.getBlockState(below);
        if (!floor.isFaceSturdy(level, below, Direction.UP)) return false;
        if (floor.is(Blocks.MAGMA_BLOCK) || floor.is(BlockTags.CAMPFIRES)) return false;

        for (BlockPos p : new BlockPos[]{feet, feet.above()}) {
            BlockState s = level.getBlockState(p);
            if (!s.getCollisionShape(level, p).isEmpty()) return false;  // 질식
            if (!s.getFluidState().isEmpty()) return false;              // 익사, 용암
            if (s.is(BlockTags.FIRE) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
                    || s.is(Blocks.CACTUS) || s.is(Blocks.WITHER_ROSE)) return false;
        }
        return true;
    }
}