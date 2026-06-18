package io.github.mousemeya.withme.gym.obs;

import com.google.protobuf.Any;
import io.github.mousemeya.withme.gym.agent.AgentControlState;
import io.github.mousemeya.withme.gym.agent.AgentRegistry;
import io.github.mousemeya.withme.gym.observation.proto.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * 实体观测数据构建器，将 Mob 的游戏状态转换为 Protobuf 格式的 {@link McObservation}。
 * <p>
 * 构建以下观测组件：
 * <ul>
 *   <li>{@code gym.self} - 自身状态（位置、速度、生命值、控制状态等）</li>
 *   <li>{@code gym.nearby_entities} - 附近实体（16 格半径内的生物信息）</li>
 *   <li>{@code gym.nearby_blocks} - 附近方块（8 格半径内的非空气方块）</li>
 *   <li>{@code gym.inventory} - 装备栏（盔甲和手持物品）</li>
 *   <li>{@code gym.world} - 世界状态（时间、天气、维度）</li>
 * </ul>
 */
public class EntityObservationBuilder {

    private static final int NEARBY_ENTITY_RADIUS = 16;  // 附近实体扫描半径（格）
    private static final int NEARBY_BLOCK_RADIUS = 8;    // 附近方块扫描半径（格）

    /** 根据 Mob 当前状态构建完整的观测数据 */
    public static McObservation build(Mob mob, String agentId) {
        var level = mob.level();
        long gameTick = level.getGameTime();

        var header = ObservationHeader.newBuilder()
            .setSchemaVersion(1)
            .setGameTick(gameTick)
            .setDimension(level.dimension().identifier().toString())
            .setAgentId(agentId)
            .build();

        var state = AgentRegistry.getState(mob);

        var builder = McObservation.newBuilder().setHeader(header);
        builder.putComponents("gym.self", Any.pack(buildSelfState(mob, state)));
        builder.putComponents("gym.nearby_entities", Any.pack(buildNearbyEntities(mob)));
        builder.putComponents("gym.nearby_blocks", Any.pack(buildNearbyBlocks(mob)));
        builder.putComponents("gym.inventory", Any.pack(buildInventory(mob)));
        builder.putComponents("gym.world", Any.pack(buildWorldState(mob)));

        return builder.build();
    }

    /** 构建自身状态组件：位置、速度、生命、朝向、控制状态等 */
    private static SelfStateComponent buildSelfState(Mob mob, AgentControlState state) {
        var b = SelfStateComponent.newBuilder()
            .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())
            .setUuid(mob.getUUID().toString())
            .setHealth(mob.getHealth())
            .setMaxHealth(mob.getMaxHealth())
            .setX(mob.getX()).setY(mob.getY()).setZ(mob.getZ())
            .setVx(mob.getDeltaMovement().x)
            .setVy(mob.getDeltaMovement().y)
            .setVz(mob.getDeltaMovement().z)
            .setYaw(mob.getYRot()).setPitch(mob.getXRot())
            .setOnGround(mob.onGround())
            .setInWater(mob.isInWater())
            .setInLava(mob.isInLava())
            .setAlive(mob.isAlive());

        if (state != null) {
            b.setNavigating(state.moveTarget != null);
            b.setAtTarget(state.moveTarget != null
                && mob.blockPosition().distToCenterSqr(state.moveTarget.x, state.moveTarget.y, state.moveTarget.z) < 2.0);
            if (mob.getTarget() != null) b.setTargetEntityId(mob.getTarget().getId());
            b.setControlMode(state.controlMode.name().toLowerCase());
        }
        return b.build();
    }

    /** 构建附近实体组件：扫描 AABB 范围内的所有 LivingEntity */
    private static NearbyEntitiesComponent buildNearbyEntities(Mob mob) {
        var b = NearbyEntitiesComponent.newBuilder();
        var level = mob.level();
        var pos = mob.position();
        var aabb = new AABB(pos.x - NEARBY_ENTITY_RADIUS, pos.y - NEARBY_ENTITY_RADIUS, pos.z - NEARBY_ENTITY_RADIUS,
            pos.x + NEARBY_ENTITY_RADIUS, pos.y + NEARBY_ENTITY_RADIUS, pos.z + NEARBY_ENTITY_RADIUS);

        for (var entity : level.getEntitiesOfClass(LivingEntity.class, aabb, e -> e != mob)) {
            double dist = mob.distanceTo(entity);
            b.addEntities(EntityView.newBuilder()
                .setEntityId(entity.getId())
                .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
                .setUuid(entity.getUUID().toString())
                .setX(entity.getX()).setY(entity.getY()).setZ(entity.getZ())
                .setDistance(dist).setLiving(true)
                .setHostile(!entity.getType().getCategory().isFriendly())
                .setAlly(entity.isAlliedTo(mob))
                .setPlayer(entity instanceof Player).setItem(false).build());
        }
        return b.build();
    }

    /** 构建附近方块组件：遍历立方体范围内所有非空气方块 */
    private static NearbyBlocksComponent buildNearbyBlocks(Mob mob) {
        var b = NearbyBlocksComponent.newBuilder();
        var level = mob.level();
        var center = mob.blockPosition();

        for (int dx = -NEARBY_BLOCK_RADIUS; dx <= NEARBY_BLOCK_RADIUS; dx++)
            for (int dy = -NEARBY_BLOCK_RADIUS; dy <= NEARBY_BLOCK_RADIUS; dy++)
                for (int dz = -NEARBY_BLOCK_RADIUS; dz <= NEARBY_BLOCK_RADIUS; dz++) {
                    var pos = center.offset(dx, dy, dz);
                    var blockState = level.getBlockState(pos);
                    if (blockState.isAir()) continue;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > NEARBY_BLOCK_RADIUS) continue;
                    b.addBlocks(BlockView.newBuilder()
                        .setX(pos.getX()).setY(pos.getY()).setZ(pos.getZ())
                        .setBlockId(BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString())
                        .setDistance(dist).build());
                }
        return b.build();
    }

    /** 构建装备栏组件：读取盔甲槽位和主/副手物品 */
    private static InventoryComponent buildInventory(Mob mob) {
        var b = InventoryComponent.newBuilder();
        int slot = 0;
        for (var eq : EquipmentSlot.VALUES) {
            if (!eq.isArmor() && eq != EquipmentSlot.MAINHAND && eq != EquipmentSlot.OFFHAND) continue;
            var stack = mob.getItemBySlot(eq);
            if (!stack.isEmpty()) b.addSlots(buildItemView(stack, slot));
            slot++;
        }
        return b.build();
    }

    /** 将单个 ItemStack 转换为 Protobuf 格式的 ItemStackView */
    private static ItemStackView buildItemView(ItemStack stack, int slot) {
        return ItemStackView.newBuilder()
            .setItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
            .setCount(stack.getCount()).setSlot(slot).setEmpty(stack.isEmpty()).build();
    }

    /** 构建世界状态组件：游戏时间、天气、维度 */
    private static WorldStateComponent buildWorldState(Mob mob) {
        var level = mob.level();
        return WorldStateComponent.newBuilder()
            .setDayTime(level.getGameTime())
            .setRaining(level.isRaining())
            .setThundering(level.isThundering())
            .setDimension(level.dimension().identifier().toString())
            .build();
    }
}
