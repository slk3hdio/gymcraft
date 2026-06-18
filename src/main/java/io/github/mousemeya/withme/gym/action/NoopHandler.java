package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import net.minecraft.world.entity.Mob;

/**
 * "gym.noop" 动作处理器 —— 无操作（空动作）。
 * <p>
 * 用于智能体选择"什么都不做"的场景，handle 方法为空实现。
 */
public class NoopHandler implements ActionHandler {

    @Override
    public String actionKey() {
        return "gym.noop";
    }

    @Override
    public boolean canHandle(Mob mob) {
        return true;
    }

    @Override
    public void handle(Mob mob, Any params) {
    }
}
