package io.github.mousemeya.gymcraft.gametest;

import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.mousemeya.gymcraft.GymCraft;

/**
 * GymCraft GameTest 注册入口（NeoForge 26.1 无数据包方式）。
 * <p>
 * 测试函数经 {@code TEST_FUNCTION} 注册表注册，测试实例在 {@link RegisterGameTestsEvent}
 * （模组总线，仅 GameTest 启用时触发）中统一装配：环境为空环境（不修改世界），
 * 结构复用原版内置的 {@code minecraft:empty}，测试逻辑自行放置方块与实体。
 * 测试类放在 main 源集：注册仅在 GameTest 启用时生效，对生产环境无副作用。
 * </p>
 */
public final class GymCraftGameTests {
    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS = DeferredRegister.create(
        BuiltInRegistries.TEST_FUNCTION,
        GymCraft.MODID
    );

    /** 原版内置空结构（always_pass 同款），测试内容全部由测试函数自行搭建。 */
    private static final Identifier EMPTY_STRUCTURE = Identifier.withDefaultNamespace("empty");
    /** 同步测试一个 tick 内完成，100 tick 超时足够冗余。 */
    private static final int MAX_TICKS = 100;

    // ===== 14.1 会话与内部状态 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SESSION_ID_STABLE =
        TEST_FUNCTIONS.register("menu_session_id_stable", () -> MenuSessionGameTests::sessionIdStableAcrossObservations);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REOPEN_NEW_ID =
        TEST_FUNCTIONS.register("menu_reopen_new_id_old_id_rejected", () -> MenuSessionGameTests::reopenGetsNewIdAndOldIdRejected);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> STALE_BLOCKS_MOVE =
        TEST_FUNCTIONS.register("menu_stale_source_blocks_move", () -> MenuSessionGameTests::staleSourceBlocksMove);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> UNRELATED_CHANGE_ALLOWS_MOVE =
        TEST_FUNCTIONS.register("menu_unrelated_slot_change_allows_move", () -> MenuSessionGameTests::unrelatedSlotChangeAllowsMove);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> STALE_ALLOWS_CLOSE =
        TEST_FUNCTIONS.register("menu_stale_state_allows_close", () -> MenuSessionGameTests::staleStateAllowsClose);

    // ===== 14.2 索引与物品栏 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> EQUIPMENT_SLOT_IDS =
        TEST_FUNCTIONS.register("inventory_equipment_slot_ids_stable", () -> MenuInventoryGameTests::equipmentSlotIdsStable);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> VILLAGER_CARRIER_SLOTS =
        TEST_FUNCTIONS.register("inventory_villager_carrier_slot_ids", () -> MenuInventoryGameTests::villagerCarrierSlotIds);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CHEST_SLOT_ALLOCATION =
        TEST_FUNCTIONS.register("inventory_chest_menu_slot_allocation", () -> MenuInventoryGameTests::chestMenuSlotIdAllocation);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SELF_MENU_SHAPE =
        TEST_FUNCTIONS.register("inventory_self_menu_shape", () -> MenuInventoryGameTests::selfMenuShape);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SLOT_IDS_INVALID_AFTER_CLOSE =
        TEST_FUNCTIONS.register("inventory_slot_ids_invalid_after_close", () -> MenuInventoryGameTests::slotIdsInvalidAfterClose);

    // ===== 14.3 物品移动 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> EMPTY_SOURCE_FAILS =
        TEST_FUNCTIONS.register("move_empty_source_fails", () -> MenuMoveGameTests::emptySourceFails);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> ILLEGAL_TARGET_FAILS =
        TEST_FUNCTIONS.register("move_illegal_target_fails", () -> MenuMoveGameTests::illegalTargetFails);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> COUNT_TRUNCATED =
        TEST_FUNCTIONS.register("move_count_truncated_to_source", () -> MenuMoveGameTests::countTruncatedToSource);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PARTIAL_TARGET_FULL =
        TEST_FUNCTIONS.register("move_partial_when_target_full", () -> MenuMoveGameTests::partialMoveWhenTargetFull);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DIFFERENT_ITEMS_NO_MERGE =
        TEST_FUNCTIONS.register("move_different_items_not_merged", () -> MenuMoveGameTests::differentItemsNotMerged);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CARRIED_ALWAYS_EMPTY =
        TEST_FUNCTIONS.register("move_carried_always_empty", () -> MenuMoveGameTests::carriedAlwaysEmpty);

    // ===== 14.4 生命周期 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DOUBLE_CHEST_MERGED =
        TEST_FUNCTIONS.register("lifecycle_double_chest_merged", () -> MenuLifecycleGameTests::doubleChestMerged);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BLOCKED_CHEST_FAILS =
        TEST_FUNCTIONS.register("lifecycle_blocked_chest_fails", () -> MenuLifecycleGameTests::blockedChestFails);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> TARGET_DESTROYED_CLOSES =
        TEST_FUNCTIONS.register("lifecycle_target_destroyed_auto_closes", () -> MenuLifecycleGameTests::targetDestroyedAutoCloses);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CLOSE_IDEMPOTENT =
        TEST_FUNCTIONS.register("lifecycle_close_exactly_once", () -> MenuLifecycleGameTests::closeExactlyOnce);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CANDIDATE_FAILURE_KEEPS_OLD =
        TEST_FUNCTIONS.register("lifecycle_candidate_failure_keeps_old", () -> MenuLifecycleGameTests::candidateFailureKeepsOld);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REOPEN_REPLACES_SESSION =
        TEST_FUNCTIONS.register("lifecycle_reopen_replaces_session", () -> MenuLifecycleGameTests::reopenReplacesSession);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> MULTI_AGENT_ISOLATION =
        TEST_FUNCTIONS.register("lifecycle_multi_agent_isolation", () -> MenuLifecycleGameTests::multiAgentIsolation);

    // ===== 14.5 聚合 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> AGGREGATE_ORDER =
        TEST_FUNCTIONS.register("aggregate_declaration_order_and_isolation", () -> ActionAggregateGameTests::aggregateDeclarationOrder);

    // ===== 14.6 按钮 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LECTERN_PAGE_BOUNDS =
        TEST_FUNCTIONS.register("button_lectern_page_bounds", () -> MenuButtonGameTests::lecternPageBounds);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LECTERN_TAKE_BOOK =
        TEST_FUNCTIONS.register("button_lectern_take_book", () -> MenuButtonGameTests::lecternTakeBook);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> UNADAPTED_MENU_FAILS =
        TEST_FUNCTIONS.register("button_unadapted_menu_fails", () -> MenuButtonGameTests::unadaptedMenuButtonFails);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> STONECUTTER_SELECT =
        TEST_FUNCTIONS.register("button_stonecutter_select_recipe", () -> MenuButtonGameTests::stonecutterSelectRecipe);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LOOM_SELECT =
        TEST_FUNCTIONS.register("button_loom_select_pattern", () -> MenuButtonGameTests::loomSelectPattern);

    // ===== 回归（端到端测试暴露的问题） =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SELF_MENU_OPEN_COMPLETED =
        TEST_FUNCTIONS.register("regression_self_menu_open_returns_completed", () -> MenuRegressionGameTests::selfMenuOpenReturnsCompleted);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BLOCK_OPEN_POSITION_SYNC =
        TEST_FUNCTIONS.register("regression_block_open_syncs_agent_player_position", () -> MenuRegressionGameTests::blockOpenSyncsAgentPlayerPosition);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SNAPSHOT_PRESERVES_EQUIPMENT =
        TEST_FUNCTIONS.register("regression_snapshot_preserves_equipment", () -> MenuRegressionGameTests::snapshotPreservesEquipment);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LECTERN_BOOK_MOVE_CONSERVES =
        TEST_FUNCTIONS.register("regression_lectern_book_move_conserves_item", () -> MenuRegressionGameTests::lecternBookMoveConservesItem);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DROP_ALL_ITEMS_CONSERVES =
        TEST_FUNCTIONS.register("regression_drop_all_items_conserves_carried", () -> MenuRegressionGameTests::dropAllItemsConservesCarriedItems);

    private GymCraftGameTests() {
    }

    /** 在 RegisterGameTestsEvent 中装配全部测试实例（空环境 + 原版空结构）。 */
    public static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
            Identifier.fromNamespaceAndPath(GymCraft.MODID, "default")
        );
        for (var holder : TEST_FUNCTIONS.getEntries()) {
            event.registerTest(
                holder.getId(),
                new FunctionGameTestInstance(
                    holder.getKey(),
                    new TestData<>(environment, EMPTY_STRUCTURE, MAX_TICKS, 0, true)
                )
            );
        }
    }
}
