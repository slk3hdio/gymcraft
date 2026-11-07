package io.github.mousemeya.gymcraft.gym.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 创建环境时保存的实体快照，用于 reset 时重建同 UUID 的 agent 实体。 */
public record EntitySnapshot(EntityType<?> type, ResourceKey<Level> dimension, CompoundTag data) {
    private static final Logger LOGGER = LoggerFactory.getLogger(EntitySnapshot.class);

    public static EntitySnapshot capture(Mob mob) {
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(mob.problemPath(), LOGGER)) {
            TagValueOutput output = TagValueOutput.createWithContext(reporter, mob.registryAccess());
            mob.saveWithoutId(output);
            return new EntitySnapshot(mob.getType(), mob.level().dimension(), output.buildResult().copy());
        }
    }

    public Mob restore() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("Cannot restore entity before server is available");
        }
        ServerLevel level = server.getLevel(this.dimension);
        if (level == null) {
            throw new IllegalStateException("Cannot restore entity because dimension is not loaded: " + this.dimension);
        }
        try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
            Entity entity = EntityType.create(
                this.type,
                TagValueInput.create(reporter, level.registryAccess(), this.data.copy()),
                level,
                EntitySpawnReason.LOAD
            ).orElseThrow(() -> new IllegalStateException("Failed to restore entity from snapshot"));
            if (entity instanceof Mob restoredMob) {
                return restoredMob;
            }
            throw new IllegalStateException("Restored entity is not a Mob: " + entity);
        }
    }

    /**
     * @return 快照捕获时的实体位置（解析 NBT {@code Pos}）
     */
    public Vec3 position() {
        ListTag pos = this.data.getList("Pos")
            .orElseThrow(() -> new IllegalStateException("Entity snapshot is missing Pos"));
        return new Vec3(
            pos.getDouble(0).orElseThrow(() -> new IllegalStateException("Entity snapshot Pos is invalid")),
            pos.getDouble(1).orElseThrow(() -> new IllegalStateException("Entity snapshot Pos is invalid")),
            pos.getDouble(2).orElseThrow(() -> new IllegalStateException("Entity snapshot Pos is invalid")));
    }
}
