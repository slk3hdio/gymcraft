package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Optional;
import java.util.Map;
import java.util.Collection;
import java.util.List;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 空动作组件 —— 智能体选择"什么都不做"时的占位动作。
 * <p>
 * 参数空间为空字典，apply() 是空实现。在 McActionSpace.sample() 中
 * 被选为默认采样输出。
 * </p>
 */
public class NoopController implements ActionComponentController<ProtoNoop> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of()); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间
    private final McSpace<Map<String, Object>> space;
    private final Collection<Class<?>> SUPPORTED_ENTITIES = List.of(Mob.class);

    public NoopController(Optional<McSpace<Map<String, Object>>> space) {
        this.space = space.orElse(DEFAULT_SPACE);
    }

    @Override
    public boolean supportEntity(Class<?> entityType) {
        for (var supported : SUPPORTED_ENTITIES) {
            if (entityType.isAssignableFrom(supported)) return true;
        }
        return false;
    }

    @Override
    public Collection<Class<?>> getSupportedEntities() {
        return SUPPORTED_ENTITIES;
    }

    @Override
    public Class<ProtoNoop> protoType() {
        return ProtoNoop.class;
    }

    @Override
    public McSpace<Map<String, Object>> space() {
        return space;
    }

    @Override
    public ProtoNoop sample() {
        return ProtoNoop.getDefaultInstance();
    }

    @Override
    public boolean contains(ProtoNoop component) {
        return component != null;
    }

    @Override
    public ActionApplyResult apply(Mob mob, ProtoNoop component) {
        return ActionApplyResult.applied(ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP, Goal.Flag.TARGET)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH)
            .eraseMemory(MemoryModuleType.LOOK_TARGET)
            .eraseMemory(MemoryModuleType.ATTACK_TARGET)
            .setMemoryWithExpiry(MemoryModuleType.ATTACK_COOLING_DOWN, true, 2)
            .stopNavigation());
    }

    @Override
    public boolean isDone(Mob mob, ProtoNoop component) { // 瞬时动作, 立即完成
        return true;
    }
}
