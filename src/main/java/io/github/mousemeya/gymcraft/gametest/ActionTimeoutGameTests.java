package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;

import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * 动作级超时：{@code timeout_seconds} 按 20 tick/秒换算，RUNNING 动作超时后
 * getState 返回 failed("action timeout") 终态；不设置超时（= 0）则不受影响。
 * <p>
 * 用 set_attack_target 构造持续 RUNNING 的动作：apply 同步设置目标即返回 RUNNING，
 * 目标存活期间 getState 保持 RUNNING，不依赖实体落地/寻路。
 * </p>
 */
public final class ActionTimeoutGameTests {
    private ActionTimeoutGameTests() {
    }

    public static void actionTimeoutFails(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var target = spawnAgent(helper, EntityType.PIG, new BlockPos(5, 1, 5));
        var setTarget = Any.pack(ProtoSetAttackTarget.newBuilder()
            .setTargetEntityId(target.getId())
            .build());
        var dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.SET_ATTACK_TARGET.get()));

        // 0.1 秒 = 2 tick 超时
        var action = ProtoMcAction.newBuilder()
            .putComponents("gymcraft:set_attack_target", setTarget)
            .setTimeoutSeconds(0.1f)
            .build();
        var state = dispatcher.apply(action).initialState();
        assertEquals(helper, ActionStatus.RUNNING, state.status(), "set_attack_target should start running");

        dispatcher.tick(action); // 第 1 tick，未超时
        assertEquals(helper, ActionStatus.RUNNING, dispatcher.getState(action).status(), "tick 1 still running");
        dispatcher.tick(action); // 第 2 tick，达到超时
        state = dispatcher.getState(action);
        assertEquals(helper, ActionStatus.FAILED, state.status(), "timeout should fail");
        assertEquals(helper, "action timeout", state.description(), "timeout description");
        assertEquals(helper, 2, state.details().get("elapsed_ticks"), "elapsed ticks");
        assertEquals(helper, (double) 0.1f, state.details().get("timeout_seconds"), "timeout seconds");

        // 不设置超时（= 0）：任意多 tick 都不触发超时
        var noTimeout = ProtoMcAction.newBuilder()
            .putComponents("gymcraft:set_attack_target", setTarget)
            .build();
        dispatcher.apply(noTimeout);
        for (int i = 0; i < 10; i++) {
            dispatcher.tick(noTimeout);
        }
        assertEquals(helper, ActionStatus.RUNNING, dispatcher.getState(noTimeout).status(), "no timeout keeps running");
        helper.succeed();
    }
}
