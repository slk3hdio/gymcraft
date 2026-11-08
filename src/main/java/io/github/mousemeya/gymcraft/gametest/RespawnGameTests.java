package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.action.ActionDispatcher;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoRespawn;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.inventory.AgentSlot;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoSelfState;
import io.github.mousemeya.gymcraft.gym.rpc.proto.ResetResponse;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

/**
 * respawn 动作与死亡重召唤生命周期测试。
 * <p>
 * 覆盖：死亡未移除/已移除两种状态下的重新召唤与运行时重绑、keep_inventory
 * 组件配置的两种取值（物品栏恢复并清理掉落 vs 空手复活保留掉落）、复活位置
 * 回到环境基线、GeneralEnv 死亡不终止 episode，以及存活时动作失败分支。
 * </p>
 */
public final class RespawnGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private RespawnGameTests() {
    }

    /**
     * 验证死亡未移除阶段的 respawn：同 UUID 新实例替换旧实例、物品栏从死亡快照
     * 恢复（基线不含该物品，只能来自快照）、掉落物被清理、复活位置回到环境基线。
     *
     * @param helper GameTest 辅助对象
     */
    public static void respawnRestoresInventoryAndClearsDrops(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        UUID agentUuid = mob.getUUID();
        Vec3 baseline = mob.position();
        McEnv env = EnvManager.attach("gymcraft:general", mob);
        // 基线捕获后再给物品：证明恢复来自死亡快照而非基线
        putIntoBackpack(mob, new ItemStack(Items.DIAMOND, 3));
        mob.teleportTo(baseline.x + 2.0, baseline.y, baseline.z);
        // 燃烧刻与氧气都是 NBT 持久化字段：带着火/溺水状态死亡，验证复活时被清理
        mob.setRemainingFireTicks(100);
        mob.setAirSupply(0);
        helper.kill(mob);
        // die() 同步完成掉落流程：keep_inventory=true 的死亡掉落被整体取消，物品只在快照里
        assertEquals(helper, 0, countDroppedItems(helper, mob.position(), Items.DIAMOND),
            "keep_inventory=true must cancel death drops entirely");

        AtomicReference<StepResponse> response = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        helper.runAfterDelay(2, () -> {
            startStep(env, respawnAction(), response, failure);

            helper.runAfterDelay(5, () -> {
                try {
                    assertTrue(helper, failure.get() == null, "respawn step raised: " + failure.get());
                    StepResponse step = response.get();
                    assertTrue(helper, step != null, "respawn step did not return");
                    assertEquals(helper, "COMPLETED", step.getObservation().getHeader().getLastActionStatus(),
                        "respawn action status");
                    assertTrue(helper, !step.getTerminated(), "general env must not terminate on death");

                    Mob respawned = assertRespawned(helper, mob, agentUuid);
                    assertEquals(helper, 20.0, (double) respawned.getHealth(), "respawned agent health");
                    assertTrue(helper, respawned.getRemainingFireTicks() <= 0,
                        "respawn must clear the NBT-persisted burning state");
                    assertEquals(helper, respawned.getMaxAirSupply(), respawned.getAirSupply(),
                        "respawn must restore full air supply");
                    ItemStack restored = firstBackpackItem(respawned);
                    assertTrue(helper, restored.is(Items.DIAMOND) && restored.getCount() == 3,
                        "respawn must restore the death inventory snapshot (backpack was cleared at death)");
                    assertTrue(helper, respawned.position().distanceTo(baseline) < 1.0,
                        "respawn position must return to the environment baseline");
                    assertTrue(helper, EnvManager.get(agentUuid).orElse(null) == env,
                        "respawn unregistered the environment");
                    assertTrue(helper, respawned.hasData(ModAttachments.AGENT_BACKPACK),
                        "backpack attachment must survive respawn via NBT round-trip");
                    ProtoSelfState self = step.getObservation().getComponentsMap()
                        .get("gymcraft:self").unpack(ProtoSelfState.class);
                    assertTrue(helper, self.getAlive(), "self observation must report the respawned agent alive");
                    assertEquals(helper, 20.0, (double) self.getHealth(), "self observation health after respawn");
                    // 复活后仍无掉落物（被取消而非事后清理）
                    assertEquals(helper, 0, countDroppedItems(helper, baseline, Items.DIAMOND),
                        "no diamond drops may appear for keep_inventory=true respawn");
                    helper.succeed();
                } catch (InvalidProtocolBufferException error) {
                    throw new IllegalStateException("Cannot unpack self observation", error);
                } finally {
                    EnvManager.close(agentUuid);
                }
            });
        });
    }

    /**
     * 验证实体被移出世界（死亡 20 tick 后）阶段的 respawn：批次经服务端 tick
     * 兜底送达，重召唤后运行时正常驱动新实例，且新实例可以再次死亡。
     *
     * @param helper GameTest 辅助对象
     */
    public static void respawnAfterRemovalRebindsRuntime(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        UUID agentUuid = mob.getUUID();
        McEnv env = EnvManager.attach("gymcraft:general", mob);
        helper.kill(mob);

        helper.runAfterDelay(25, () -> {
            assertTrue(helper, mob.isRemoved(), "agent must be removed before respawn");
            AtomicReference<StepResponse> response = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            startStep(env, respawnAction(), response, failure);

            helper.runAfterDelay(5, () -> {
                assertTrue(helper, failure.get() == null, "respawn after removal raised: " + failure.get());
                StepResponse step = response.get();
                assertTrue(helper, step != null, "respawn after removal did not return");
                assertEquals(helper, "COMPLETED", step.getObservation().getHeader().getLastActionStatus(),
                    "respawn after removal action status");

                Mob respawned = assertRespawned(helper, mob, agentUuid);

                // 重绑后的运行时必须能继续驱动新实例完成动作
                AtomicReference<StepResponse> followUp = new AtomicReference<>();
                AtomicReference<Throwable> followUpFailure = new AtomicReference<>();
                startStep(env, noopAction(), followUp, followUpFailure);

                helper.runAfterDelay(4, () -> {
                    assertTrue(helper, followUpFailure.get() == null,
                        "follow-up step raised: " + followUpFailure.get());
                    assertTrue(helper, followUp.get() != null, "follow-up step did not return");
                    assertEquals(helper, "COMPLETED",
                        followUp.get().getObservation().getHeader().getLastActionStatus(),
                        "follow-up action status on respawned agent");
                    assertTrue(helper, !followUp.get().getTerminated(),
                        "general env must not terminate after respawn");

                    // 重召唤的新实例必须可以再次正常死亡（完整 die() 流程与死亡快照）
                    helper.kill(respawned);
                    helper.runAfterDelay(2, () -> {
                        try {
                            assertTrue(helper, respawned.isDeadOrDying(),
                                "respawned agent must be killable again");
                            assertTrue(helper, respawned.hasData(ModAttachments.DEATH_INVENTORY),
                                "second death must capture a fresh death inventory snapshot");
                            helper.succeed();
                        } finally {
                            EnvManager.close(agentUuid);
                        }
                    });
                });
            });
        });
    }

    /**
     * 验证 keep_inventory=false（reset options 组件配置）：复活后空手，
     * 死亡掉落物保留在世界中。
     *
     * @param helper GameTest 辅助对象
     */
    public static void respawnWithoutInventoryKeepsDrops(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        UUID agentUuid = mob.getUUID();
        McEnv env = EnvManager.attach("gymcraft:general", mob);

        // 经组件配置通道关闭 keep_inventory（reset 会以基线实例替换当前实体）
        AtomicReference<ResetResponse> resetResponse = new AtomicReference<>();
        AtomicReference<Throwable> resetFailure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                resetResponse.set(env.reset(null, Map.of(
                    "components", Map.of("respawn", Map.of("keep_inventory", false)))));
            } catch (Throwable error) {
                resetFailure.set(error);
            }
        });

        helper.runAfterDelay(3, () -> {
            assertTrue(helper, resetFailure.get() == null, "reset with respawn options raised: " + resetFailure.get());
            assertTrue(helper, resetResponse.get() != null, "reset with respawn options did not return");
            Mob current = currentAgent(helper, agentUuid);
            assertTrue(helper, current != null && current != mob, "reset did not replace the agent entity");
            putIntoBackpack(current, new ItemStack(Items.DIAMOND, 3));
            helper.kill(current);
            // keep_inventory=false 不捕获快照也不取消掉落：死亡瞬间即原版掉落
            assertEquals(helper, 1, countDroppedItems(helper, current.position(), Items.DIAMOND),
                "keep_inventory=false death must drop the vanilla way");

            AtomicReference<StepResponse> response = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            startStep(env, respawnAction(), response, failure);

            helper.runAfterDelay(5, () -> {
                try {
                    assertTrue(helper, failure.get() == null, "keep_inventory=false respawn raised: " + failure.get());
                    StepResponse step = response.get();
                    assertTrue(helper, step != null, "keep_inventory=false respawn did not return");
                    assertEquals(helper, "COMPLETED", step.getObservation().getHeader().getLastActionStatus(),
                        "keep_inventory=false respawn status");

                    Mob respawned = assertRespawned(helper, current, agentUuid);
                    assertTrue(helper, firstBackpackItem(respawned).isEmpty(),
                        "keep_inventory=false must respawn with an empty inventory");
                    assertEquals(helper, 1, countDroppedItems(helper, current.position(), Items.DIAMOND),
                        "death drops must stay in the world when keep_inventory=false");
                    helper.succeed();
                } finally {
                    EnvManager.close(agentUuid);
                }
            });
        });
    }

    /**
     * 验证存活实体上 respawn 直接失败。
     *
     * @param helper GameTest 辅助对象
     */
    public static void respawnFailsWhenAlive(GameTestHelper helper) {
        Mob mob = spawnAgent(helper, EntityType.HUSK, new BlockPos(2, 1, 2));
        ActionDispatcher dispatcher = new ActionDispatcher(mob, List.of(ActionComponents.RESPAWN.get()));

        var state = dispatcher.apply(respawnAction()).initialState();
        assertEquals(helper, ActionStatus.FAILED, state.status(), "alive agent respawn status");
        assertEquals(helper, "agent is alive, nothing to respawn", state.description(),
            "alive agent respawn description");
        helper.succeed();
    }

    /**
     * 断言旧实例已被替换：同 UUID 的新实例存活于世界且旧实例已移除。
     *
     * @param helper GameTest 辅助对象
     * @param oldMob 死亡前的旧实例
     * @param agentUuid agent 实体 UUID
     * @return 世界中当前存活的实例
     */
    private static Mob assertRespawned(GameTestHelper helper, Mob oldMob, UUID agentUuid) {
        assertTrue(helper, oldMob.isRemoved(), "old agent instance was not removed by respawn");
        Mob current = currentAgent(helper, agentUuid);
        assertTrue(helper, current != null, "respawned agent is missing from the level");
        assertTrue(helper, current != oldMob, "respawn reused the old entity instance");
        assertTrue(helper, current.isAlive(), "respawned agent is not alive");
        return current;
    }

    /** @return 世界中当前承载该 UUID 的 Mob。 */
    private static Mob currentAgent(GameTestHelper helper, UUID agentUuid) {
        ServerLevel level = helper.getLevel();
        Entity entity = level.getEntity(agentUuid);
        return entity instanceof Mob mob ? mob : null;
    }

    /**
     * 统计结构范围内指定物品的掉落物实体数量。
     *
     * @param helper GameTest 辅助对象
     * @param center 搜索中心（世界坐标）
     * @param item   目标物品
     * @return 掉落物实体数量
     */
    private static int countDroppedItems(GameTestHelper helper, Vec3 center, Item item) {
        AABB box = new AABB(center.add(-8.0, -8.0, -8.0), center.add(8.0, 8.0, 8.0));
        return helper.getLevel()
            .getEntitiesOfClass(ItemEntity.class, box, drop -> drop.getItem().is(item))
            .size();
    }

    /**
     * 把物品写入背包首格（背包内容死亡必掉且槽位必清空，测试行为确定）。
     *
     * @param mob   目标 agent
     * @param stack 待写入物品
     */
    private static void putIntoBackpack(Mob mob, ItemStack stack) {
        backpackSlot(mob).setItem(stack);
    }

    /** @return 背包首格当前物品（无背包时为空栈）。 */
    private static ItemStack firstBackpackItem(Mob mob) {
        return AgentInventoryLayout.resolve(mob).slots().stream()
            .filter(slot -> slot.slotId() >= AgentInventoryLayout.EQUIPMENT_SLOT_COUNT)
            .map(AgentSlot::getItem)
            .findFirst()
            .orElse(ItemStack.EMPTY);
    }

    /** @return 背包首格槽位视图。 */
    private static AgentSlot backpackSlot(Mob mob) {
        return AgentInventoryLayout.resolve(mob).slots().stream()
            .filter(slot -> slot.slotId() >= AgentInventoryLayout.EQUIPMENT_SLOT_COUNT)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("agent has no backpack slot"));
    }

    /** @return 仅包含 respawn 组件的动作。 */
    private static ProtoMcAction respawnAction() {
        return ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:respawn")
            .setPayload(Any.pack(ProtoRespawn.getDefaultInstance()))
            .build();
    }

    /** @return 仅包含 noop 组件的动作。 */
    private static ProtoMcAction noopAction() {
        return ProtoMcAction.newBuilder()
            .setComponentId("gymcraft:noop")
            .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
            .build();
    }

    /**
     * 在虚拟线程中执行同步 env.step，避免阻塞 GameTest 服务端线程。
     *
     * @param env      待执行环境
     * @param action   动作消息
     * @param response 成功响应容器
     * @param failure  异常容器
     */
    private static void startStep(
        McEnv env,
        ProtoMcAction action,
        AtomicReference<StepResponse> response,
        AtomicReference<Throwable> failure
    ) {
        Thread.startVirtualThread(() -> {
            try {
                response.set(env.step(List.of(action), 0.0F));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
    }
}
