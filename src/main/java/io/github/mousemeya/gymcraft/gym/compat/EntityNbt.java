package io.github.mousemeya.gymcraft.gym.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/** 1.21.1 实体 NBT 桥；保存独立副本，恢复时保留调用方指定的实体类型与 UUID。 */
public final class EntityNbt {
    /** 禁止实例化静态桥。 */
    private EntityNbt() {}

    /** 捕获实体数据；参数 entity 为实体，返回可独立修改的 NBT。 */
    public static CompoundTag capture(Entity entity) {
        return entity.saveWithoutId(new CompoundTag()).copy();
    }

    /** 恢复未入世界的实体；参数 type、data、level 分别为类型、数据和世界，返回实体。 */
    public static Entity restore(EntityType<?> type, CompoundTag data, ServerLevel level) {
        CompoundTag copy = data.copy();
        copy.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
        return EntityType.create(copy, level).orElseThrow(() -> new IllegalStateException("Failed to restore entity from snapshot"));
    }
}
