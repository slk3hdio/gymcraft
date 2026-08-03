package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.level.pathfinder.Path;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 寻路动作组件 —— 将实体导航到指定三维坐标。
 * <p>
 * 参数空间包含坐标、速度修正值、停止距离和超时时间。
 * </p>
 */
public class MoveToController extends AbstractActionComponentController<ProtoMoveTo> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MoveToController.class);

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "stop_distance", new BoxSpace(0, 128, 1)
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    public MoveToController() {
    }

    @Override
    public Class<ProtoMoveTo> protoType() {
        return ProtoMoveTo.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoMoveTo component) {
        return component != null && this.space().contains(Map.of(
            "x", new double[] { component.getX() },
            "y", new double[] { component.getY() },
            "z", new double[] { component.getZ() },
            "stop_distance", new double[] { component.getStopDistance() }
        ));
    }

    @Override
    public ActionApplyResult apply(Mob mob, ProtoMoveTo component) {
        boolean moved = mob.getNavigation().moveTo(component.getX(), component.getY(), component.getZ(), 1.0);
        Path path = mob.getNavigation().getPath();
        var policy = ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH);
        if (!moved) {
            policy.stopNavigation();
        }
        ActionState initialState = moved
            ? ActionState.running("navigating to target", pathDetails(mob, path, component))
            : ActionState.failed(failureDescription(mob, path, component), pathDetails(mob, path, component));
        LOGGER.info(
            "GymCraft MoveTo apply entity={} moved={} target=({}, {}, {}) state={} details={}",
            mob.getUUID(),
            moved,
            component.getX(),
            component.getY(),
            component.getZ(),
            initialState.status(),
            initialState.details()
        );
        return ActionApplyResult.applied(policy, initialState);
    }

    @Override
    public ActionState getState(Mob mob, ProtoMoveTo component) {
        double dx = mob.getX() - component.getX();
        double dy = mob.getY() - component.getY();
        double dz = mob.getZ() - component.getZ();
        double stop = component.getStopDistance();
        double horizontalDistSq = dx * dx + dz * dz;
        double horizontalDist = Math.sqrt(horizontalDistSq);
        if (horizontalDistSq <= stop * stop) {
            ActionState state = ActionState.completed("reached target", Map.of(
                "horizontal_distance", horizontalDist,
                "vertical_delta", dy,
                "stop_distance", stop));
            LOGGER.info("GymCraft MoveTo state entity={} status={} details={}", mob.getUUID(), state.status(), state.details());
            return state;
        }
        if (mob.getNavigation().isDone()) {
            ActionState state = ActionState.failed("navigation ended before reaching target", Map.of(
                "horizontal_distance", horizontalDist,
                "vertical_delta", dy,
                "stop_distance", stop,
                "navigation_done", true));
            LOGGER.info("GymCraft MoveTo state entity={} status={} description={} details={}", mob.getUUID(), state.status(), state.description(), state.details());
            return state;
        }
        ActionState state = ActionState.running("navigating", Map.of(
            "horizontal_distance", horizontalDist,
            "vertical_delta", dy));
        LOGGER.info("GymCraft MoveTo state entity={} status={} details={}", mob.getUUID(), state.status(), state.details());
        return state;
    }

    private static Map<String, Object> pathDetails(Mob mob, Path path, ProtoMoveTo component) {
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("x", component.getX());
        details.put("y", component.getY());
        details.put("z", component.getZ());
        details.put("stop_distance", component.getStopDistance());
        details.put("mob_type", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
        details.put("navigation_class", mob.getNavigation().getClass().getSimpleName());
        details.put("no_ai", mob.isNoAi());
        details.put("on_ground", mob.onGround());
        details.put("in_liquid", mob.isInLiquid());
        details.put("in_water", mob.isInWater());
        details.put("passenger", mob.isPassenger());
        details.put("can_update_ground_path", mob.onGround() || mob.isInLiquid() || mob.isPassenger());
        details.put("mob_block", mob.blockPosition().toShortString());
        BlockPos below = mob.blockPosition().below();
        details.put("block_below", BuiltInRegistries.BLOCK.getKey(mob.level().getBlockState(below).getBlock()).toString());
        BlockPos targetBlock = BlockPos.containing(component.getX(), component.getY(), component.getZ());
        details.put("target_block", targetBlock.toShortString());
        details.put("target_chunk_loaded", mob.level().getChunkSource().getChunkNow(
            SectionPos.blockToSectionCoord(targetBlock.getX()),
            SectionPos.blockToSectionCoord(targetBlock.getZ())) != null);
        details.put("path_null", path == null);
        if (path != null) {
            details.put("path_node_count", path.getNodeCount());
            details.put("path_can_reach", path.canReach());
            details.put("path_done", path.isDone());
            details.put("path_dist_to_target", path.getDistToTarget());
            details.put("path_target", path.getTarget().toShortString());
        }
        return details;
    }

    private static String failureDescription(Mob mob, Path path, ProtoMoveTo component) {
        BlockPos targetBlock = BlockPos.containing(component.getX(), component.getY(), component.getZ());
        boolean targetChunkLoaded = mob.level().getChunkSource().getChunkNow(
            SectionPos.blockToSectionCoord(targetBlock.getX()),
            SectionPos.blockToSectionCoord(targetBlock.getZ())) != null;
        if (!targetChunkLoaded) {
            return "target chunk is not loaded";
        }
        if (path == null) {
            return "path not found";
        }
        if (!path.canReach()) {
            return "target cannot be reached by pathfinder";
        }
        return "unreachable target";
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoMoveTo> {
        @Override
        public MoveToController create() {
            return new MoveToController();
        }
    }
}
