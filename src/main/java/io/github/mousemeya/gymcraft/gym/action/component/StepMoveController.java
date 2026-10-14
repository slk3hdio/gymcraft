package io.github.mousemeya.gymcraft.gym.action.component;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoStepMove;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 单步移动控制组件 —— 在每个 tick 中直接操作 Mob 的姿态和位移。
 * <p>
 * 适用于需要帧级精细控制的场景（如强化学习中的连续控制策略）。
 * 与 MoveToActionComponent 的路径规划不同，此组件直接控制移动、跳跃和视角。
 * </p>
 */
public class StepMoveController extends AbstractActionComponentController<ProtoStepMove> {
    private static final McSpace<ProtoStepMove> DEFAULT_SPACE = MessageSpace.<ProtoStepMove>builder(ProtoStepMove.getDescriptor())
        .range("forward", -1, 1)
        .range("strafe_right", -1, 1)
        .range("yaw_delta", -180, 180)
        .bool("jump")
        .build();

    public StepMoveController(Mob mob) {
        super(mob);
    }

    @Override
    public Class<ProtoStepMove> protoType() {
        return ProtoStepMove.class;
    }

    @Override
    public McSpace<ProtoStepMove> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public ActionApplyResult apply(ProtoStepMove component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        mob.getMoveControl().strafe(component.getForward(), component.getStrafeRight());
        if (component.getJump()) {
            mob.getJumpControl().jump();
        }
        if (component.getYawDelta() != 0) {
            mob.setYRot(mob.getYRot() + component.getYawDelta());
        }
        return ActionApplyResult.applied(ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH)
            .eraseMemory(MemoryModuleType.LOOK_TARGET)
            .stopNavigation(),
            ActionState.completed("step movement applied"));
    }

    /**
     * 瞬时动作不会被运行时 tick；实现仅返回已应用终态以满足接口契约。
     *
     * @param component 当前动作
     * @return 完成状态
     */
    @Override
    public ActionState tick(ProtoStepMove component) {
        return ActionState.completed("step movement applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoStepMove, StepMoveController> {
        @Override
        public StepMoveController create(Mob mob) {
            return new StepMoveController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return StepMoveController 的运行时类型
         */
        @Override
        public Class<StepMoveController> componentType() {
            return StepMoveController.class;
        }
    }
}
