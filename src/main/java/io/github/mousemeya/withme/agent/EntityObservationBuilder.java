package io.github.mousemeya.withme.agent;

import com.google.protobuf.Any;
import io.github.mousemeya.withme.gym.observation.proto.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

public class EntityObservationBuilder {

    private static final int NEARBY_ENTITY_RADIUS = 16;
    private static final int NEARBY_BLOCK_RADIUS = 8;

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

    private static SelfStateComponent buildSelfState(Mob mob, AgentControlState state) {
        var b = SelfStateComponent.newBuilder()
            .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())
            .setUuid(mob.getUUID().toString())
            .setHealth(mob.getHealth())
            .setMaxHealth(mob.getMaxHealth())
            .setX(mob.getX())
            .setY(mob.getY())
            .setZ(mob.getZ())
            .setVx(mob.getDeltaMovement().x)
            .setVy(mob.getDeltaMovement().y)
            .setVz(mob.getDeltaMovement().z)
            .setYaw(mob.getYRot())
            .setPitch(mob.getXRot())
            .setOnGround(mob.onGround())
            .setInWater(mob.isInWater())
            .setInLava(mob.isInLava())
            .setAlive(mob.isAlive());

        if (state != null) {
            b.setNavigating(state.moveTarget != null);
            b.setAtTarget(state.moveTarget != null
                && mob.blockPosition().distToCenterSqr(state.moveTarget.x, state.moveTarget.y, state.moveTarget.z) < 2.0);
            if (mob.getTarget() != null) {
                b.setTargetEntityId(mob.getTarget().getId());
            }
            b.setControlMode(state.controlMode.name().toLowerCase());
        }

        return b.build();
    }

    private static NearbyEntitiesComponent buildNearbyEntities(Mob mob) {
        var b = NearbyEntitiesComponent.newBuilder();
        var level = mob.level();
        var pos = mob.position();
        var aabb = new AABB(pos.x - NEARBY_ENTITY_RADIUS, pos.y - NEARBY_ENTITY_RADIUS, pos.z - NEARBY_ENTITY_RADIUS,
            pos.x + NEARBY_ENTITY_RADIUS, pos.y + NEARBY_ENTITY_RADIUS, pos.z + NEARBY_ENTITY_RADIUS);

        for (var entity : level.getEntitiesOfClass(LivingEntity.class, aabb, e -> e != mob)) {
            double dist = mob.distanceTo(entity);
            var view = EntityView.newBuilder()
                .setEntityId(entity.getId())
                .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString())
                .setUuid(entity.getUUID().toString())
                .setX(entity.getX())
                .setY(entity.getY())
                .setZ(entity.getZ())
                .setDistance(dist)
                .setLiving(true)
                .setHostile(!entity.getType().getCategory().isFriendly())
                .setAlly(entity.isAlliedTo(mob))
                .setPlayer(entity instanceof Player)
                .setItem(false)
                .build();
            b.addEntities(view);
        }

        return b.build();
    }

    private static NearbyBlocksComponent buildNearbyBlocks(Mob mob) {
        var b = NearbyBlocksComponent.newBuilder();
        var level = mob.level();
        var center = mob.blockPosition();

        for (int dx = -NEARBY_BLOCK_RADIUS; dx <= NEARBY_BLOCK_RADIUS; dx++) {
            for (int dy = -NEARBY_BLOCK_RADIUS; dy <= NEARBY_BLOCK_RADIUS; dy++) {
                for (int dz = -NEARBY_BLOCK_RADIUS; dz <= NEARBY_BLOCK_RADIUS; dz++) {
                    var pos = center.offset(dx, dy, dz);
                    var blockState = level.getBlockState(pos);
                    if (blockState.isAir()) continue;
                    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (dist > NEARBY_BLOCK_RADIUS) continue;

                    b.addBlocks(BlockView.newBuilder()
                        .setX(pos.getX())
                        .setY(pos.getY())
                        .setZ(pos.getZ())
                        .setBlockId(BuiltInRegistries.BLOCK.getKey(blockState.getBlock()).toString())
                        .setDistance(dist)
                        .build());
                }
            }
        }

        return b.build();
    }

    private static InventoryComponent buildInventory(Mob mob) {
        var b = InventoryComponent.newBuilder();
        int slot = 0;
        for (EquipmentSlot equipmentSlot : EquipmentSlot.VALUES) {
            if (!equipmentSlot.isArmor() && equipmentSlot != EquipmentSlot.MAINHAND && equipmentSlot != EquipmentSlot.OFFHAND)
                continue;
            var stack = mob.getItemBySlot(equipmentSlot);
            if (!stack.isEmpty()) {
                b.addSlots(buildItemView(stack, slot));
            }
            slot++;
        }
        return b.build();
    }

    private static ItemStackView buildItemView(ItemStack stack, int slot) {
        return ItemStackView.newBuilder()
            .setItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
            .setCount(stack.getCount())
            .setSlot(slot)
            .setEmpty(stack.isEmpty())
            .build();
    }

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
