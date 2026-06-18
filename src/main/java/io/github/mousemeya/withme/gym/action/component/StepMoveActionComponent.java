package io.github.mousemeya.withme.gym.action.component;

import io.github.mousemeya.withme.gym.action.ActionComponent;
import io.github.mousemeya.withme.gym.action.proto.StepMoveComponent;
import io.github.mousemeya.withme.gym.space.BooleanSpace;
import io.github.mousemeya.withme.gym.space.BoxSpace;
import io.github.mousemeya.withme.gym.space.DictSpace;
import io.github.mousemeya.withme.gym.space.McSpace;
import net.minecraft.world.entity.Mob;

import java.util.Map;

/**
 * 单步移动控制组件 —— 在每个 tick 中直接操作 Mob 的姿态和位移，而非通过 Navigation API。
 * <p>
 * 参数空间（DictSpace）：
 * <ul>
 *   <li>{@code forward} —— 前进/后退量 [-1, 1]</li>
 *   <li>{@code strafe_right} —— 右移/左移量 [-1, 1]</li>
 *   <li>{@code yaw_delta} —— 偏航角增量（度）[-180, 180]</li>
 *   <li>{@code pitch_delta} —— 俯仰角增量（度）[-90, 90]</li>
 *   <li>{@code jump} —— 是否跳跃</li>
 * </ul>
 * 此组件适用于需要帧级精细控制的场景（如强化学习中的连续控制策略）。
 * </p>
 */
public class StepMoveActionComponent implements ActionComponent<StepMoveComponent> {
    private static final McSpace<?> SPACE = new DictSpace(Map.of(
        "forward", new BoxSpace(-1, 1, 1),
        "strafe_right", new BoxSpace(-1, 1, 1),
        "yaw_delta", new BoxSpace(-180, 180, 1),
        "pitch_delta", new BoxSpace(-90, 90, 1),
        "jump", new BooleanSpace()
    ));

    @Override
    public Class<StepMoveComponent> protoType() {
        return StepMoveComponent.class;
    }

    @Override
    public McSpace<?> space() {
        return SPACE;
    }

    @Override
    public StepMoveComponent sample() {
        return StepMoveComponent.getDefaultInstance();
    }

    @Override
    public boolean contains(StepMoveComponent component) {
        return Float.isFinite(component.getForward())
            && Float.isFinite(component.getStrafeRight())
            && Float.isFinite(component.getYawDelta())
            && Float.isFinite(component.getPitchDelta())
            && component.getForward() >= -1 && component.getForward() <= 1
            && component.getStrafeRight() >= -1 && component.getStrafeRight() <= 1;
    }

    @Override
    public void apply(Mob mob, StepMoveComponent component) {
        mob.getMoveControl().strafe(component.getForward(), component.getStrafeRight());
        if (component.getJump()) {
            mob.getJumpControl().jump();
        }
        if (component.getYawDelta() != 0 || component.getPitchDelta() != 0) {
            mob.setYRot(mob.getYRot() + component.getYawDelta());
            mob.setXRot(mob.getXRot() + component.getPitchDelta());
        }
    }
}
