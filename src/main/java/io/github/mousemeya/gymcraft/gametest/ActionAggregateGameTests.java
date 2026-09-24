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
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * 单组件动作协议回归测试。
 */
public final class ActionAggregateGameTests {
    private ActionAggregateGameTests() {
    }

    public static void aggregateDeclarationOrder(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        var dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.NOOP.get()));
        var action = ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:noop")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build();

        var state = dispatcher.apply(action).initialState();
        assertEquals(helper, ActionStatus.COMPLETED, state.status(), "single component status");

        var unknown = ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:unknown")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build();
        var unknownState = dispatcher.apply(unknown).initialState();
        assertEquals(helper, ActionStatus.FAILED, unknownState.status(), "unknown component status");
        assertEquals(helper, "gymcraft:unknown", unknownState.details().get("component_id"),
            "unknown component detail");
        helper.succeed();
    }
}
