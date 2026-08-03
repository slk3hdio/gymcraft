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
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoStepMove;
import io.github.mousemeya.gymcraft.gym.space.BooleanSpace;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 单步移动控制组件 —— 在每个 tick 中直接操作 Mob 的姿态和位移。
 * <p>
 * 适用于需要帧级精细控制的场景（如强化学习中的连续控制策略）。
 * 与 MoveToActionComponent 的路径规划不同，此组件直接控制移动、跳跃和视角。
 * </p>
 */
public class StepMoveController extends AbstractActionComponentController<ProtoStepMove> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "forward", new BoxSpace(-1, 1, 1),
        "strafe_right", new BoxSpace(-1, 1, 1),
        "yaw_delta", new BoxSpace(-180, 180, 1),
        "pitch_delta", new BoxSpace(-90, 90, 1),
        "jump", new BooleanSpace()
    )); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    public StepMoveController() {
    }

    @Override
    public Class<ProtoStepMove> protoType() {
        return ProtoStepMove.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoStepMove component) {
        return component != null && this.space().contains(Map.of(
            "forward", new double[] { component.getForward() },
            "strafe_right", new double[] { component.getStrafeRight() },
            "yaw_delta", new double[] { component.getYawDelta() },
            "pitch_delta", new double[] { component.getPitchDelta() },
            "jump", component.getJump()
        ));
    }

    @Override
    public ActionApplyResult apply(Mob mob, ProtoStepMove component) {
        mob.getMoveControl().strafe(component.getForward(), component.getStrafeRight());
        if (component.getJump()) {
            mob.getJumpControl().jump();
        }
        if (component.getYawDelta() != 0 || component.getPitchDelta() != 0) {
            mob.setYRot(mob.getYRot() + component.getYawDelta());
            mob.setXRot(mob.getXRot() + component.getPitchDelta());
        }
        return ActionApplyResult.applied(ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP)
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH)
            .eraseMemory(MemoryModuleType.LOOK_TARGET)
            .stopNavigation(),
            ActionState.completed("step movement applied"));
    }

    @Override
    public ActionState getState(Mob mob, ProtoStepMove component) {
        return ActionState.completed("step movement applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoStepMove> {
        @Override
        public StepMoveController create() {
            return new StepMoveController();
        }
    }
}
