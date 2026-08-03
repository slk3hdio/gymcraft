package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
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
public class NoopController extends AbstractActionComponentController<ProtoNoop> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of()); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    public NoopController() {
    }

    @Override
    public Class<ProtoNoop> protoType() {
        return ProtoNoop.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoNoop component) {
        return component != null && this.space().contains(Map.of());
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
            .stopNavigation(),
            ActionState.completed("noop"));
    }

    @Override
    public ActionState getState(Mob mob, ProtoNoop component) {
        return ActionState.completed("noop");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoNoop> {
        @Override
        public NoopController create() {
            return new NoopController();
        }
    }
}
