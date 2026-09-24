package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.AABB;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItem;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItemEntityTarget;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.IronGolemWardenEnv;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyEntities;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;

/**
 * 铁傀儡战斗环境的场地恢复、里程碑奖励、治疗交互与终态测试。
 * <p>
 * 测试不关心真实战斗过程：Warden 生成后立即禁用 AI，傀儡结构由服务端线程
 * 直接摆放触发原版生成，胜利通过 {@code warden.kill()} 注入。
 * </p>
 */
public final class IronGolemWardenEnvGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private IronGolemWardenEnvGameTests() {
    }

    /**
     * 验证 reset 重建战斗场、发放建材并生成 Warden，close 恢复原世界并清走任务实体。
     *
     * @param helper GameTest 辅助对象
     */
    public static void resetAndCloseRestoreArena(GameTestHelper helper) {
        BlockPos mobFloor = new BlockPos(5, 1, 5);
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, mobFloor);
        BlockPos origin = BlockPos.containing(mob.position());
        BlockPos originalChestPos = origin.offset(2, 0, -1);
        helper.getLevel().setBlock(originalChestPos, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(originalChestPos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));

        IronGolemWardenEnv env = new IronGolemWardenEnv(testId("restore"), mob);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(7, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                // 场地恢复断言需要 500 tick 窗口，冻结 Warden 避免它趁机击杀 Agent 干扰断言。
                runOnServer(helper, () -> helper.getLevel()
                    .getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst().setNoAi(true));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            assertTrue(helper, failure.get() == null, "iron_golem_warden reset failed: " + failure.get());
            Mob restoredMob = (Mob) helper.getLevel().getEntity(mob.getUUID());
            assertTrue(helper, restoredMob != null, "reset Mob was not restored");
            // 场地模板：基岩地板、玻璃围墙与基岩顶盖，内部清空。
            assertTrue(helper, helper.getLevel().getBlockState(origin.offset(0, -1, 0)).is(Blocks.BEDROCK),
                "bedrock floor missing");
            assertTrue(helper, helper.getLevel().getBlockState(origin.offset(6, 0, 0)).is(Blocks.GLASS),
                "glass wall missing");
            assertTrue(helper, helper.getLevel().getBlockState(origin.offset(0, 5, 0)).is(Blocks.BEDROCK),
                "bedrock ceiling missing");
            assertTrue(helper, helper.getLevel().getBlockState(origin).is(Blocks.AIR),
                "arena interior was not cleared");
            // 建材：主手 4 铁块，背包南瓜与 64 铁锭。
            var layout = AgentInventoryLayout.resolve(restoredMob);
            assertTrue(helper, restoredMob.getMainHandItem().is(Items.IRON_BLOCK)
                && restoredMob.getMainHandItem().getCount() == 4, "main hand iron blocks mismatch");
            assertTrue(helper, layout.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT).getItem().is(Items.CARVED_PUMPKIN),
                "carved pumpkin missing from backpack");
            assertTrue(helper, layout.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + 1).getItem().is(Items.IRON_INGOT)
                && layout.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + 1).getItem().getCount() == 64,
                "iron ingots missing from backpack");
            assertTrue(helper, helper.getLevel().getEntitiesOfClass(Warden.class, arenaBounds(origin)).size() == 1,
                "warden was not spawned");
            // Warden 必须自带虚弱（无限时长），保证 Agent 不会被近战秒杀。
            Warden spawnedWarden = helper.getLevel().getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst();
            assertTrue(helper, spawnedWarden.hasEffect(net.minecraft.world.effect.MobEffects.WEAKNESS),
                "warden is missing the weakness effect");

            env.close();
            assertTrue(helper, helper.getLevel().getBlockState(originalChestPos).is(Blocks.CHEST),
                "close did not restore original chest: " + helper.getLevel().getBlockState(originalChestPos));
            ChestBlockEntity restoredChest = (ChestBlockEntity) helper.getLevel().getBlockEntity(originalChestPos);
            assertTrue(helper, restoredChest != null, "restored chest block entity missing");
            assertEquals(helper, 3, restoredChest.getItem(0).getCount(), "restored chest content mismatch");
            assertTrue(helper, helper.getLevel().getEntitiesOfClass(Warden.class, arenaBounds(origin)).isEmpty(),
                "close did not remove the warden");
            helper.succeed();
        });
    }

    /**
     * 验证搭铁块、生成傀儡、铁锭治疗与击杀 Warden 的里程碑与终态。
     *
     * @param helper GameTest 辅助对象
     */
    public static void golemSpawnHealAndVictory(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(mob.position());
        IronGolemWardenEnv env = new IronGolemWardenEnv(testId("victory"), mob);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<StepResponse> golemStep = new AtomicReference<>();
        AtomicReference<StepResponse> healStep = new AtomicReference<>();
        AtomicReference<StepResponse> victoryStep = new AtomicReference<>();
        AtomicReference<Float> healthAfterHeal = new AtomicReference<>();

        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    // 测试只验证环境逻辑，冻结 Warden 避免真实战斗干扰。
                    Warden warden = helper.getLevel().getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst();
                    warden.setNoAi(true);
                    // 原版铁傀儡图案：底座 1 块 + 中层 3 块横排。
                    helper.getLevel().setBlock(origin.offset(2, 0, 0), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                    helper.getLevel().setBlock(origin.offset(1, 1, 0), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                    helper.getLevel().setBlock(origin.offset(2, 1, 0), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                    helper.getLevel().setBlock(origin.offset(3, 1, 0), Blocks.IRON_BLOCK.defaultBlockState(), 3);
                });

                runOnServer(helper, () -> {
                    // 最后放置雕刻南瓜，触发原版铁傀儡生成（图案方块被清除）。
                    helper.getLevel().setBlock(origin.offset(2, 2, 0), Blocks.CARVED_PUMPKIN.defaultBlockState(), 3);
                    IronGolem golem = helper.getLevel().getEntitiesOfClass(IronGolem.class, arenaBounds(origin)).getFirst();
                    // 傀儡会主动攻击僵尸 Agent；本测试只验证环境逻辑，冻结傀儡避免误杀。
                    golem.setNoAi(true);
                    golem.hurt(helper.getLevel().damageSources().generic(), 30.0F);
                });
                golemStep.set(step(env, "gymcraft:noop", ProtoNoop.getDefaultInstance()));

                IronGolem golem = helper.getLevel().getEntitiesOfClass(IronGolem.class, arenaBounds(origin)).getFirst();
                Mob currentMob = (Mob) helper.getLevel().getEntity(mob.getUUID());
                int ingotSlot = findItemSlot(currentMob, Items.IRON_INGOT);
                healStep.set(step(env, "gymcraft:use_item", ProtoUseItem.newBuilder().setSlotId(ingotSlot)
                    .setEntity(ProtoUseItemEntityTarget.newBuilder().setEntityId(golem.getId())).build()));
                healthAfterHeal.set(golem.getHealth());

                runOnServer(helper, () -> helper.getLevel()
                    .getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst().kill());
                victoryStep.set(step(env, "gymcraft:noop", ProtoNoop.getDefaultInstance()));
            } catch (Throwable error) {
                failure.set(error);
            }
        });

        helper.runAfterDelay(10000, () -> {
            try {
                assertTrue(helper, failure.get() == null, "golem victory test failed: " + failure.get());
                StepResponse golemSpawn = golemStep.get();
                assertTrue(helper, golemSpawn != null && golemSpawn.getInfo().contains("\"golem_spawned\":\"achieved\""),
                    "golem_spawned milestone missing: " + (golemSpawn == null ? "no step" : golemSpawn.getInfo()));
                // 30 伤害后 70 生命，铁锭治疗 +25。
                assertEquals(helper, 95.0F, healthAfterHeal.get(), "iron ingot did not heal the golem by 25");
                StepResponse victory = victoryStep.get();
                assertTrue(helper, victory != null && victory.getTerminated(), "warden kill did not terminate");
                assertTrue(helper, victory.getInfo().contains("\"success\":true"),
                    "success info missing: " + (victory == null ? "no step" : victory.getInfo()));
                assertTrue(helper, victory.getInfo().contains("\"golem_healed_at_least_once\":\"achieved\""),
                    "golem_healed milestone missing: " + (victory == null ? "no step" : victory.getInfo()));
                assertTrue(helper, victory.getReward() >= 10.0, "warden_slain reward missing");
                // nearby_entities 观测必须携带血量字段，供客户端直接读取 Warden 状态。
                StepResponse anyStep = golemStep.get();
                assertTrue(helper, anyStep != null, "no step available for observation check");
                ProtoNearbyEntities entities = nearbyEntities(anyStep);
                var wardenView = entities.getEntitiesList().stream()
                    .filter(view -> view.getEntityType().equals("minecraft:warden")).findFirst();
                assertTrue(helper, wardenView.isPresent(), "warden missing from nearby_entities observation");
                assertEquals(helper, 500.0F, wardenView.orElseThrow().getMaxHealth(), "warden max_health mismatch");
                assertTrue(helper, wardenView.orElseThrow().getHealth() > 0.0F, "warden health not populated");
                helper.succeed();
            } catch (InvalidProtocolBufferException error) {
                throw new RuntimeException(error);
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证步数上限截断与 Agent 死亡终止。
     *
     * @param helper GameTest 辅助对象
     */
    public static void failureAndStepLimit(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(mob.position());
        IronGolemWardenEnv env = new IronGolemWardenEnv(testId("failure"), mob);
        AtomicReference<StepResponse> truncatedResponse = new AtomicReference<>();
        AtomicReference<StepResponse> deadResponse = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(
                    IronGolemWardenEnv.MAX_STEPS_OPTION, 1,
                    AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true
                ));
                // 冻结 Warden，保证截断断言不受战斗过程影响。
                runOnServer(helper, () -> helper.getLevel()
                    .getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst().setNoAi(true));
                truncatedResponse.set(step(env, "gymcraft:noop", ProtoNoop.getDefaultInstance()));

                env.reset(2, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    // 冻结 Warden 后再杀死 Agent，避免死因受战斗过程干扰。
                    Warden warden = helper.getLevel().getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst();
                    warden.setNoAi(true);
                    java.util.Objects.requireNonNull(helper.getLevel().getEntity(mob.getUUID())).kill();
                });
                deadResponse.set(step(env, "gymcraft:noop", ProtoNoop.getDefaultInstance()));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(10000, () -> {
            try {
                assertTrue(helper, failure.get() == null, "failure-state test failed: " + failure.get());
                assertTrue(helper, truncatedResponse.get() != null && truncatedResponse.get().getTruncated(),
                    "max_steps did not truncate");
                assertTrue(helper, !truncatedResponse.get().getTerminated(), "step limit incorrectly terminated");
                assertTrue(helper, deadResponse.get() != null && deadResponse.get().getTerminated(),
                    "agent death did not terminate");
                assertTrue(helper, deadResponse.get().getInfo().contains("agent_dead"),
                    "agent_dead reason missing: " + deadResponse.get().getInfo());
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证南瓜放置在无效位置（未生成傀儡）时立即判定不可恢复失败并终止回合：
     * 经真实 {@code set_block} 放置南瓜（自动消耗背包物品），同一步即应终止。
     *
     * @param helper GameTest 辅助对象
     */
    public static void invalidPatternFailsUnrecoverably(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(mob.position());
        IronGolemWardenEnv env = new IronGolemWardenEnv(testId("invalid_pattern"), mob);
        AtomicReference<StepResponse> wastedStep = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> {
                    // 冻结 Warden，保证失败原因不被 agent_dead 抢占。
                    helper.getLevel().getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst().setNoAi(true);
                });
                // 南瓜放置在空位（脚下相邻格），不构成有效底座。
                BlockPos pumpkinPos = origin.offset(0, 0, 1);
                wastedStep.set(step(env, "gymcraft:set_block",
                    io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetBlock.newBuilder()
                        .setX(pumpkinPos.getX()).setY(pumpkinPos.getY()).setZ(pumpkinPos.getZ())
                        .setBlock("minecraft:carved_pumpkin").build()));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(10000, () -> {
            try {
                assertTrue(helper, failure.get() == null, "invalid pattern test failed: " + failure.get());
                StepResponse wasted = wastedStep.get();
                assertTrue(helper, wasted != null && wasted.getTerminated(),
                    "pumpkin-wasted build did not terminate");
                assertTrue(helper, wasted != null && wasted.getInfo().contains("golem_unspawnable"),
                    "golem_unspawnable reason missing: " + (wasted == null ? "no step" : wasted.getInfo()));
                assertTrue(helper, wasted != null && wasted.getInfo().contains("\"golem_spawned\":\"failed\""),
                    "golem_spawned not marked failed");
                assertTrue(helper, wasted != null && wasted.getInfo().contains("carved pumpkin already placed"),
                    "pumpkin failure reason missing: " + (wasted == null ? "no step" : wasted.getInfo()));
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证跨回合（含 Agent 死亡后）reset 重新发放全部建材。
     *
     * @param helper GameTest 辅助对象
     */
    public static void suppliesReissuedAcrossResets(GameTestHelper helper) {
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(mob.position());
        IronGolemWardenEnv env = new IronGolemWardenEnv(testId("resupply"), mob);
        AtomicReference<StepResponse> placeStep = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> helper.getLevel()
                    .getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst().setNoAi(true));
                // 消耗部分物资：放置一个铁块，再把南瓜换到主手。
                BlockPos placePos = origin.offset(1, 0, 1);
                placeStep.set(step(env, "gymcraft:set_block",
                    io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetBlock.newBuilder()
                        .setX(placePos.getX()).setY(placePos.getY()).setZ(placePos.getZ())
                        .setBlock("minecraft:iron_block").build()));
                // 模拟上一回合以 Agent 死亡收场。
                runOnServer(helper, () -> java.util.Objects.requireNonNull(helper.getLevel().getEntity(mob.getUUID())).kill());
                step(env, "gymcraft:noop", ProtoNoop.getDefaultInstance());
                env.reset(2, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                runOnServer(helper, () -> helper.getLevel()
                    .getEntitiesOfClass(Warden.class, arenaBounds(origin)).getFirst().setNoAi(true));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(10000, () -> {
            try {
                assertTrue(helper, failure.get() == null, "resupply test failed: " + failure.get());
                assertTrue(helper, placeStep.get() != null && !placeStep.get().getInfo().contains("action_error"),
                    "iron block placement failed: " + (placeStep.get() == null ? "no step" : placeStep.get().getInfo()));
                Mob current = (Mob) helper.getLevel().getEntity(mob.getUUID());
                assertTrue(helper, current != null && current.isAlive(), "agent was not restored after death");
                var layout = AgentInventoryLayout.resolve(current);
                assertTrue(helper, current.getMainHandItem().is(Items.IRON_BLOCK)
                    && current.getMainHandItem().getCount() == 4,
                    "main hand iron blocks not reissued: " + current.getMainHandItem());
                assertTrue(helper, layout.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT).getItem().is(Items.CARVED_PUMPKIN),
                    "carved pumpkin not reissued");
                assertTrue(helper, layout.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + 1).getItem().is(Items.IRON_INGOT)
                    && layout.slot(AgentInventoryLayout.EQUIPMENT_SLOT_COUNT + 1).getItem().getCount() == 64,
                    "iron ingots not reissued");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 从统一物品栏查找指定物品的实时逻辑槽。
     *
     * @param mob 当前受控 Mob
     * @param item 需要查找的物品
     * @return 包含该物品的逻辑 slot_id
     */
    private static int findItemSlot(Mob mob, net.minecraft.world.item.Item item) {
        return AgentInventoryLayout.resolve(mob).slots().stream()
            .filter(slot -> slot.getItem().is(item))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("item not found in Agent inventory: " + item))
            .slotId();
    }

    /**
     * 构建只含一个动作组件的 step 请求。
     *
     * @param env 任务环境
     * @param componentId 组件注册 ID
     * @param payload 组件 protobuf
     * @return step 响应
     */
    private static StepResponse step(IronGolemWardenEnv env, String componentId, Message payload) {
        ProtoMcAction action = ProtoMcAction.newBuilder()
            .setComponentId(componentId)
            .setPayload(Any.pack(payload))
            .build();
        return env.step(List.of(action), 0.0F);
    }

    /**
     * 在服务端线程执行世界修改并等待完成；环境交互运行在虚拟线程上。
     *
     * @param helper GameTest 辅助对象
     * @param task 服务端世界操作
     */
    private static void runOnServer(GameTestHelper helper, Runnable task) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        helper.getLevel().getServer().execute(() -> {
            try {
                task.run();
                completion.complete(null);
            } catch (Throwable error) {
                completion.completeExceptionally(error);
            }
        });
        completion.join();
    }

    /**
     * 解包 step 响应中的附近实体观测。
     *
     * @param response step 响应
     * @return 附近实体观测
     */
    private static ProtoNearbyEntities nearbyEntities(StepResponse response) throws InvalidProtocolBufferException {
        return response.getObservation().getComponentsOrThrow("gymcraft:nearby_entities")
            .unpack(ProtoNearbyEntities.class);
    }

    /**
     * 以战斗场原点为中心的实体查询范围。
     *
     * @param origin 战斗场原点
     * @return 覆盖整个战斗场的 AABB
     */
    private static AABB arenaBounds(BlockPos origin) {
        return new AABB(
            origin.getX() - 6, origin.getY() - 1, origin.getZ() - 6,
            origin.getX() + 7, origin.getY() + 6, origin.getZ() + 7
        );
    }

    /**
     * 构建测试专用环境 ID。
     *
     * @param suffix 测试场景后缀
     * @return 唯一测试 ID
     */
    private static ResourceLocation testId(String suffix) {
        return ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "iron_golem_warden_" + suffix + "_test");
    }
}
