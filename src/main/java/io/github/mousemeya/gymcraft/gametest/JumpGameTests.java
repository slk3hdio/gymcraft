package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

import java.util.List;

import com.google.protobuf.Any;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;

import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoJump;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * 跳跃动作：apply 立即返回 COMPLETED，跳跃意图经 {@code JumpControl}
 * 传递到 Mob —— {@code JumpControl.tick()}（游戏每 tick 的常规驱动）后
 * {@code jumping} 字段为 true（1.21.1 无公开 isJumping()，经 AT 放开字段）。
 */
public final class JumpGameTests {
    private JumpGameTests() {
    }

    public static void jumpAppliedAndExecuted(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.JUMP.get()));
        var action = ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:jump")
            .setPayload(Any.pack(ProtoJump.getDefaultInstance()))
            .build();

        var state = dispatcher.apply(action).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "jump should complete instantly");
        assertEquals(helper, ActionStatus.COMPLETED, dispatcher.getState(action).status(), "jump state stays completed");

        // JumpControl.tick() 是游戏每 tick 对跳跃控制的常规驱动
        mob.getJumpControl().tick();
        assertTrue(helper, mob.jumping, "mob should be jumping after JumpControl tick");
        helper.succeed();
    }
}
