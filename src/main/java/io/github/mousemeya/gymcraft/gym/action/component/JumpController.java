package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoJump;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 跳跃组件 —— 瞬时动作，命令 Mob 立即跳一次。
 * <p>
 * 参数空间为空字典。apply() 通过 {@code JumpControl.jump()} 提交跳跃意图，
 * 实际起跳由原版跳跃控制在 Mob 的下一个 tick 中于着地时执行。
 * </p>
 */
public class JumpController extends AbstractActionComponentController<ProtoJump> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of()); // TODO: 使用Message.getDescriptorForType()获取字段元数据以自动生成默认空间

    public JumpController(Mob mob) {
        super(mob);
    }

    @Override
    public Class<ProtoJump> protoType() {
        return ProtoJump.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoJump component) {
        return component != null && this.space().contains(Map.of());
    }

    @Override
    public ActionApplyResult apply(ProtoJump component) {
        this.mob().getJumpControl().jump();
        return ActionApplyResult.applied(ActionControlPolicy.none()
            .disableGoalFlags(Goal.Flag.JUMP),
            ActionState.completed("jump applied"));
    }

    @Override
    public ActionState getState(ProtoJump component) {
        return ActionState.completed("jump applied");
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoJump> {
        @Override
        public JumpController create(Mob mob) {
            return new JumpController(mob);
        }
    }
}
