package io.github.mousemeya.gymcraft.gym.action.component;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 空动作组件 —— 智能体选择"什么都不做"时的占位动作。
 * <p>
 * 参数空间为无字段的空消息；apply() 只提交抑制自主行为的控制策略，
 * 不驱动任何移动或注视。
 * </p>
 */
public class NoopController extends AbstractActionComponentController<ProtoNoop> {
    private static final McSpace<ProtoNoop> DEFAULT_SPACE = MessageSpace.of(ProtoNoop.getDescriptor());

    public NoopController(Mob mob) {
        super(mob);
    }

    @Override
    public Class<ProtoNoop> protoType() {
        return ProtoNoop.class;
    }

    @Override
    public McSpace<ProtoNoop> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public ActionApplyResult apply(ProtoNoop component) {
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
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

    /**
     * 瞬时动作不会被运行时 tick；实现仅返回已应用终态以满足接口契约。
     *
     * @param component 当前动作
     * @return 完成状态
     */
    @Override
    public ActionState tick(ProtoNoop component) {
        return ActionState.completed("noop");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoNoop, NoopController> {
        @Override
        public NoopController create(Mob mob) {
            return new NoopController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return NoopController 的运行时类型
         */
        @Override
        public Class<NoopController> componentType() {
            return NoopController.class;
        }
    }
}
