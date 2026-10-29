package io.github.mousemeya.gymcraft.gym.compat;

import net.minecraft.world.entity.ai.control.MoveControl;
import io.github.mousemeya.gymcraft.gym.action.navigation.SwimMoveControl;

/** 兼容 1.21.1 缺失的移动等待接口；切换控制器时必须同步清除子控制器目标。 */
public final class MoveControlBridge {
    /** 禁止实例化静态控制桥。 */
    private MoveControlBridge() {}

    /** 清除移动目标；参数 control 为当前控制器，无返回值。 */
    public static void stop(MoveControl control) {
        control.operation = MoveControl.Operation.WAIT;
        if (control instanceof SwimMoveControl swimming) swimming.setWait();
    }
}
