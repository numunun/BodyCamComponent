package numunun.bodycamcomponent.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.Level;
import numunun.bodycamcomponent.config.BodyCamConfig;
import numunun.bodycamcomponent.registry.ModEntities;

import java.util.Optional;
import java.util.UUID;

public class CorpseEntity extends Entity {
    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    private static final EntityDataAccessor<String> NAME =
            SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.STRING);

    private int age = 0;
    private int lifetime = 0;  // 틱, 0이면 무제한

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
    }

    public static void spawnFor(ServerPlayer p) {
        CorpseEntity c = new CorpseEntity(ModEntities.CORPSE.get(), p.level());
        c.moveTo(p.getX(), p.getY(), p.getZ(), p.yBodyRot, 0f);
        c.entityData.set(OWNER, Optional.of(p.getUUID()));
        c.entityData.set(NAME, p.getScoreboardName());
        c.lifetime = BodyCamConfig.CORPSE_SECONDS.get() * 20;
        p.level().addFreshEntity(c);
    }

    public Optional<UUID> getOwner() { return entityData.get(OWNER); }
    public String getOwnerName() { return entityData.get(NAME); }

    @Override
    protected void defineSynchedData() {
        entityData.define(OWNER, Optional.empty());
        entityData.define(NAME, "");
    }

    @Override
    public void tick() {
        super.tick();
        // 간단한 중력: 바닥까지 떨어짐
        setDeltaMovement(getDeltaMovement().add(0, -0.04, 0));
        move(MoverType.SELF, getDeltaMovement());
        setDeltaMovement(getDeltaMovement().multiply(0.9, 0.98, 0.9));

        if (!level().isClientSide && lifetime > 0 && ++age >= lifetime) discard();
    }

    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean isAttackable() { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Owner")) entityData.set(OWNER, Optional.of(tag.getUUID("Owner")));
        entityData.set(NAME, tag.getString("OwnerName"));
        age = tag.getInt("Age");
        lifetime = tag.getInt("Lifetime");
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        getOwner().ifPresent(id -> tag.putUUID("Owner", id));
        tag.putString("OwnerName", getOwnerName());
        tag.putInt("Age", age);
        tag.putInt("Lifetime", lifetime);
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }
}