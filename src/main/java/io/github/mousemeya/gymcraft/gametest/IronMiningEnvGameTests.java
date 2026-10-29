package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.infoJson;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.proto.Move;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoBreakBlock;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoCloseMenu;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveMenuItem;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoOpenMenu;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSelfMenuTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetBlock;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.IronMiningEnv;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMenuObservation;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoNearbyItems;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;

/**
 * 铁矿工具链环境的场地恢复、奖励终态与真实动作闭环测试。
 */
public final class IronMiningEnvGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private IronMiningEnvGameTests() {
    }

    /**
     * 验证 reset 物化场景资源并清空初始物品；close 不做世界恢复（场景保留在世界）。
     *
     * @param helper GameTest 辅助对象
     */
    public static void resetMaterializesScenario(GameTestHelper helper) {
        BlockPos mobFloor = new BlockPos(5, 1, 5);
        Mob mob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, mobFloor);
        BlockPos origin = BlockPos.containing(mob.position());
        BlockPos originalChestPos = origin.offset(2, 0, -1);
        helper.getLevel().setBlock(originalChestPos, Blocks.CHEST.defaultBlockState(), 3);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(originalChestPos);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        mob.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.GOLD_INGOT));

        IronMiningEnv env = new IronMiningEnv(testId("restore"), mob,
            ScenarioStore.loadBundled(helper.getLevel(), "iron_mining").orElseThrow());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread.startVirtualThread(() -> {
            try {
                env.reset(7, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        helper.runAfterDelay(500, () -> {
            assertTrue(helper, failure.get() == null, "iron_mining reset failed: " + failure.get());
            Mob restoredMob = (Mob) helper.getLevel().getEntity(mob.getUUID());
            assertTrue(helper, restoredMob != null, "reset Mob was not restored");
            assertTrue(helper, AgentInventoryLayout.resolve(restoredMob).slots().stream()
                .allMatch(slot -> slot.getItem().isEmpty()), "reset did not clear the Agent inventory");
            assertTrue(helper, helper.getLevel().getBlockState(origin.offset(2, 0, -1)).is(Blocks.OAK_LOG),
                "log template was not created");
            assertTrue(helper, helper.getLevel().getBlockState(origin.offset(0, 0, 3)).is(Blocks.IRON_ORE),
                "iron ore template was not created");

            env.close();
            assertTrue(helper, helper.getLevel().getBlockState(origin.offset(2, 0, -1)).is(Blocks.OAK_LOG),
                "close must not restore the world (scene should remain)");
            helper.succeed();
        });
    }

    /**
     * 使用真实动作完成原木、工作台、木镐、圆石、石镐、铁矿和粗铁拾取全链路。
     *
     * @param helper GameTest 辅助对象
     */
    public static void completeToolProgression(GameTestHelper helper) {
        Mob originalMob = MenuGameTestSupport.spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(5, 1, 5));
        BlockPos origin = BlockPos.containing(originalMob.position());
        IronMiningEnv env = new IronMiningEnv(testId("progression"), originalMob,
            ScenarioStore.loadBundled(helper.getLevel(), "iron_mining").orElseThrow());
        AtomicReference<StepResponse> finalResponse = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean done = new AtomicBoolean();

        Thread.startVirtualThread(() -> {
            try {
                env.reset(1, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                List<BlockPos> logs = List.of(
                    origin.offset(2, 0, -1), origin.offset(2, 0, 0), origin.offset(2, 0, 1));
                // 原木空手即可挖掘：reset 后主手为空
                for (BlockPos log : logs) {
                    StepResponse broken = step(env, "gymcraft:break_block", breakBlock(log));
                    pickUpIfPresent(env, nearbyItems(broken), Items.OAK_LOG);
                }

                long selfSession = openSelf(env);
                move(env, selfSession, List.of(
                    transfer(0, 35, 3, 1),
                    transfer(34, 7, 4, 3)
                ));
                move(env, selfSession, List.of(
                    transfer(7, 35, 1, 1), transfer(7, 36, 1, 1),
                    transfer(7, 37, 1, 1), transfer(7, 38, 1, 1),
                    transfer(34, 0, 1, 1)
                ));
                close(env, selfSession);

                BlockPos tablePos = origin.offset(0, 0, 1);
                // 拾取组件会寻路接近掉落物，挖完原木后 agent 已漂离原点，碰撞箱可能压住
                // 按原点计算的工作台放置格（set_block 按 BlockItem#canPlace 检查实体阻挡）；
                // 放置前把 agent 归位到原点消除漂移。世界修改必须在服务端线程执行
                CompletableFuture<Void> repositioned = new CompletableFuture<>();
                helper.getLevel().getServer().execute(() -> {
                    try {
                        Mob current = currentMob(helper, originalMob);
                        current.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
                        repositioned.complete(null);
                    } catch (Throwable error) {
                        repositioned.completeExceptionally(error);
                    }
                });
                repositioned.join();
                StepResponse tablePlaced = step(env, "gymcraft:set_block", ProtoSetBlock.newBuilder()
                    .setX(tablePos.getX()).setY(tablePos.getY()).setZ(tablePos.getZ())
                    .setBlock("minecraft:crafting_table").build());
                if (infoJson(tablePlaced.getInfo()).contains("\"status\":\"failed\"")) {
                    throw new IllegalStateException("crafting table placement failed: " + tablePlaced.getInfo());
                }
                long tableSession = openBlock(env, tablePos);
                move(env, tableSession, List.of(
                    transfer(7, 35, 1, 1), transfer(7, 38, 1, 1),
                    transfer(34, 8, 4, 1),
                    transfer(7, 35, 1, 1), transfer(7, 36, 1, 1), transfer(7, 37, 1, 1),
                    transfer(8, 39, 1, 1), transfer(8, 42, 1, 1),
                    transfer(34, 0, 1, 1)
                ));
                close(env, tableSession);

                List<BlockPos> stones = List.of(
                    origin.offset(-2, 0, -1), origin.offset(-2, 0, 0), origin.offset(-2, 0, 1));
                for (BlockPos stone : stones) {
                    // 木镐合成时已移入主手（slot 0），挖掘固定使用主手持物
                    if (!currentMob(helper, originalMob).getMainHandItem().is(Items.WOODEN_PICKAXE)) {
                        throw new IllegalStateException("wooden pickaxe missing from main hand");
                    }
                    StepResponse broken = step(env, "gymcraft:break_block", breakBlock(stone));
                    pickUpIfPresent(env, nearbyItems(broken), Items.COBBLESTONE);
                }

                tableSession = openBlock(env, tablePos);
                int cobblestoneSlot = findItemSlot(currentMob(helper, originalMob), Items.COBBLESTONE);
                move(env, tableSession, List.of(
                    transfer(0, 10, 1, 1),
                    transfer(cobblestoneSlot, 35, 1, 1), transfer(cobblestoneSlot, 36, 1, 1),
                    transfer(cobblestoneSlot, 37, 1, 1),
                    transfer(8, 39, 1, 1), transfer(8, 42, 1, 1),
                    transfer(34, 0, 1, 1)
                ));
                close(env, tableSession);

                if (!currentMob(helper, originalMob).getMainHandItem().is(Items.STONE_PICKAXE)) {
                    throw new IllegalStateException("stone pickaxe missing from main hand");
                }
                StepResponse ironBroken = step(env, "gymcraft:break_block",
                    breakBlock(origin.offset(0, 0, 3)));
                finalResponse.set(pickUpIfPresent(env, nearbyItems(ironBroken), Items.RAW_IRON));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.set(true);
            }
        });

        // 等异步工具链实际结束再断言，避免不限速 tick 提前耗尽固定延迟。
        helper.succeedWhen(() -> {
            assertTrue(helper, done.get(), "waiting for tool progression");
            try {
                assertTrue(helper, failure.get() == null, "tool progression failed: " + failure.get());
                StepResponse response = finalResponse.get();
                assertTrue(helper, response != null, "tool progression did not finish");
                assertTrue(helper, response.getTerminated(), "raw iron pickup did not terminate successfully");
                assertTrue(helper, infoJson(response.getInfo()).contains("\"success\":true"), "success info missing: " + response.getInfo());
                Mob current = (Mob) helper.getLevel().getEntity(originalMob.getUUID());
                assertTrue(helper, AgentInventoryLayout.resolve(current).slots().stream()
                    .anyMatch(slot -> slot.getItem().is(Items.RAW_IRON)), "raw iron is not in Agent inventory");
            } finally {
                env.close();
            }
        });
    }

    /**
     * 从统一物品栏查找指定物品的实时逻辑槽，避免测试依赖装备优先落槽的具体结果。
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
    private static StepResponse step(IronMiningEnv env, String componentId, Message payload) {
        ProtoMcAction action = ProtoMcAction.newBuilder()
            .setComponentId(componentId)
            .setPayload(Any.pack(payload))
            .build();
        return env.step(List.of(action), 0.0F);
    }

    /**
     * 构建指定坐标的挖掘消息（固定使用主手持有物）。
     *
     * @param pos 方块坐标
     * @return 挖掘 protobuf
     */
    private static ProtoBreakBlock breakBlock(BlockPos pos) {
        return ProtoBreakBlock.newBuilder()
            .setX(pos.getX()).setY(pos.getY()).setZ(pos.getZ())
            .build();
    }

    /** 取 reset 后同一 UUID 的当前受控 Mob 实例。 */
    private static Mob currentMob(GameTestHelper helper, Mob original) {
        return (Mob) helper.getLevel().getEntity(original.getUUID());
    }

    /**
     * 打开 self 菜单并返回新会话 ID。
     *
     * @param env 任务环境
     * @return 菜单会话 ID
     */
    private static long openSelf(IronMiningEnv env) throws InvalidProtocolBufferException {
        StepResponse response = step(env, "gymcraft:open_menu", ProtoOpenMenu.newBuilder()
            .setSelf(ProtoSelfMenuTarget.getDefaultInstance()).build());
        return menu(response).getSessionId();
    }

    /**
     * 打开指定工作台菜单并返回新会话 ID。
     *
     * @param env 任务环境
     * @param pos 工作台坐标
     * @return 菜单会话 ID
     */
    private static long openBlock(IronMiningEnv env, BlockPos pos) throws InvalidProtocolBufferException {
        StepResponse response = step(env, "gymcraft:open_menu", ProtoOpenMenu.newBuilder()
            .setBlock(io.github.mousemeya.gymcraft.gym.action.proto.ProtoBlockMenuTarget.newBuilder()
                .setX(pos.getX()).setY(pos.getY()).setZ(pos.getZ()))
            .build());
        return menu(response).getSessionId();
    }

    /**
     * 执行一批顺序菜单移动。
     *
     * @param env 任务环境
     * @param sessionId 当前菜单会话 ID
     * @param moves 顺序移动列表
     * @return step 响应
     */
    private static StepResponse move(IronMiningEnv env, long sessionId, List<Move> moves) {
        return step(env, "gymcraft:move_menu_item", ProtoMoveMenuItem.newBuilder()
            .setSessionId(sessionId).addAllMoves(moves).build());
    }

    /**
     * 构建单个菜单移动描述。
     *
     * @param source 来源 slot_id
     * @param target 目标 slot_id
     * @param count 单次数量
     * @param repeat 重复次数
     * @return 移动 protobuf
     */
    private static Move transfer(int source, int target, int count, int repeat) {
        return Move.newBuilder().setSourceSlotId(source).setTargetSlotId(target)
            .setCount(count).setRepeat(repeat).build();
    }

    /**
     * 关闭指定菜单会话。
     *
     * @param env 任务环境
     * @param sessionId 当前菜单会话 ID
     */
    private static void close(IronMiningEnv env, long sessionId) {
        step(env, "gymcraft:close_menu", ProtoCloseMenu.newBuilder().setSessionId(sessionId).build());
    }

    /**
     * 从附近物品观测选择指定物品并执行拾取。
     *
     * @param env 任务环境
     * @param nearbyItems 附近掉落物观测
     * @param expected 期望物品
     * @return 拾取 step 响应
     */
    private static StepResponse pickUpIfPresent(IronMiningEnv env, ProtoNearbyItems nearbyItems, net.minecraft.world.item.Item expected) {
        var item = nearbyItems.getItemsList().stream()
            .filter(view -> view.getItem().getItemId().equals(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(expected).toString()))
            .findFirst();
        if (item.isEmpty()) {
            return step(env, "gymcraft:noop", ProtoNoop.getDefaultInstance());
        }
        return step(env, "gymcraft:pick_up_item", ProtoPickUpItem.newBuilder()
            .setEntityId(item.orElseThrow().getEntityId()).build());
    }

    /**
     * 解包 step 响应中的菜单观测。
     *
     * @param response step 响应
     * @return 菜单观测
     */
    private static ProtoMenuObservation menu(StepResponse response) throws InvalidProtocolBufferException {
        return response.getObservation().getComponentsOrThrow("gymcraft:menu").unpack(ProtoMenuObservation.class);
    }

    /**
     * 解包 step 响应中的附近掉落物观测。
     *
     * @param response step 响应
     * @return 附近掉落物观测
     */
    private static ProtoNearbyItems nearbyItems(StepResponse response) throws InvalidProtocolBufferException {
        return response.getObservation().getComponentsOrThrow("gymcraft:nearby_items").unpack(ProtoNearbyItems.class);
    }

    /**
     * 构建测试专用环境 ID。
     *
     * @param suffix 测试场景后缀
     * @return 唯一测试 ID
     */
    private static ResourceLocation testId(String suffix) {
        return ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "iron_mining_" + suffix + "_test");
    }
}
