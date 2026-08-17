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
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoCloseMenu;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.registry.ActionComponents;

/**
 * 14.5 ActionState 聚合：多组件严格按环境声明顺序执行，
 * description 含 {@code [component_id] description}，details 按组件 ID 隔离。
 */
public final class ActionAggregateGameTests {
    private ActionAggregateGameTests() {
    }

    public static void aggregateDeclarationOrder(GameTestHelper helper) {
        var mob = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        // 声明顺序 noop 在前；proto map 故意先放 close_menu，验证执行顺序不依赖 map 顺序
        var dispatcher = new ActionDispatcher(mob, List.of(
            ActionComponents.NOOP.get(),
            ActionComponents.CLOSE_MENU.get()
        ));
        var action = ProtoMcAction.newBuilder()
            .putComponents("gymcraft:close_menu", Any.pack(ProtoCloseMenu.newBuilder().setSessionId(42).build()))
            .putComponents("gymcraft:noop", Any.pack(ProtoNoop.getDefaultInstance()))
            .putComponents("bogus", Any.pack(ProtoNoop.getDefaultInstance()))
            .build();

        var state = dispatcher.apply(action).initialState();
        // close_menu 无会话失败，优先级最高 → 总体 FAILED
        assertEquals(helper, ActionStatus.FAILED, state.status(), "aggregate status");
        assertEquals(helper,
            "[gymcraft:noop] noop; [gymcraft:close_menu] no open menu session; [bogus] unknown action component",
            state.description(), "aggregate description");
        // details 以组件注册 ID 为 key、按执行顺序隔离
        assertEquals(helper, List.of("gymcraft:noop", "gymcraft:close_menu", "bogus"),
            List.copyOf(state.details().keySet()), "aggregate details order");
        assertEquals(helper, java.util.Map.of(), state.details().get("gymcraft:noop"), "noop details must be empty");
        assertEquals(helper, java.util.Map.of("key", "bogus"), state.details().get("bogus"),
            "bogus details should carry the unknown key");
        helper.succeed();
    }
}
