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
 * 普通测试复用 {@code minecraft:empty}；使用物品测试采用覆盖完整场景的 8×6×8 空结构，
 * 保证跨 tick 消费时实体所在区块被框架加载。测试逻辑自行放置方块与实体。
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
    /** 持续消费依赖实体 tick，专用结构边界必须覆盖所有 Agent 与目标，避免只强加载原点。 */
    private static final Identifier USE_ITEM_STRUCTURE = Identifier.fromNamespaceAndPath(GymCraft.MODID, "use_item_empty");
    /** 同步测试一个 tick 内完成，100 tick 超时足够冗余。 */
    private static final int MAX_TICKS = 100;

    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BATCH_MOVES_ORDER =
        TEST_FUNCTIONS.register("menu_batch_moves_order", () -> MenuSessionGameTests::batchMovesInOrder);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BATCH_MOVES_FAILURE =
        TEST_FUNCTIONS.register("menu_batch_moves_failure", () -> MenuSessionGameTests::batchMoveStopsOnFailure);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BATCH_MOVES_STALE =
        TEST_FUNCTIONS.register("menu_batch_moves_stale", () -> MenuSessionGameTests::batchMoveChecksAllBaselines);

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
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REPEAT_MOVES_MULTIPLE =
        TEST_FUNCTIONS.register("move_repeat_moves_multiple_times", () -> MenuMoveGameTests::repeatMovesMultipleTimes);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REPEAT_STOPS_SOURCE_EMPTY =
        TEST_FUNCTIONS.register("move_repeat_stops_when_source_empty", () -> MenuMoveGameTests::repeatStopsWhenSourceEmpty);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REPEAT_CRAFTING_OUTPUTS =
        TEST_FUNCTIONS.register("move_repeat_takes_consecutive_crafting_outputs", () -> MenuMoveGameTests::repeatTakesConsecutiveCraftingOutputs);

    // ===== 14.4 生命周期 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DOUBLE_CHEST_MERGED =
        TEST_FUNCTIONS.register("lifecycle_double_chest_merged", () -> MenuLifecycleGameTests::doubleChestMerged);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> BLOCKED_CHEST_FAILS =
        TEST_FUNCTIONS.register("lifecycle_blocked_chest_fails", () -> MenuLifecycleGameTests::blockedChestFails);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> TARGET_DESTROYED_CLOSES =
        TEST_FUNCTIONS.register("lifecycle_target_destroyed_auto_closes", () -> MenuLifecycleGameTests::targetDestroyedAutoCloses);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CLOSE_IDEMPOTENT =
        TEST_FUNCTIONS.register("lifecycle_close_exactly_once", () -> MenuLifecycleGameTests::closeExactlyOnce);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LIFECYCLE_ENTITY_EVENTS_CLOSE_SESSIONS =
        TEST_FUNCTIONS.register("lifecycle_entity_events_close_sessions", () -> MenuLifecycleGameTests::entityLifecycleEventsCloseSessions);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CANDIDATE_FAILURE_KEEPS_OLD =
        TEST_FUNCTIONS.register("lifecycle_candidate_failure_keeps_old", () -> MenuLifecycleGameTests::candidateFailureKeepsOld);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REOPEN_REPLACES_SESSION =
        TEST_FUNCTIONS.register("lifecycle_reopen_replaces_session", () -> MenuLifecycleGameTests::reopenReplacesSession);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> MULTI_AGENT_ISOLATION =
        TEST_FUNCTIONS.register("lifecycle_multi_agent_isolation", () -> MenuLifecycleGameTests::multiAgentIsolation);

    // ===== 14.5 聚合 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> AGGREGATE_ORDER =
        TEST_FUNCTIONS.register("aggregate_declaration_order_and_isolation", () -> ActionAggregateGameTests::aggregateDeclarationOrder);

    // ===== 14.7 动作超时 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> ACTION_TIMEOUT_FAILS =
        TEST_FUNCTIONS.register("action_timeout_fails", () -> ActionTimeoutGameTests::actionTimeoutFails);

    // ===== 方块放置 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SET_BLOCK_SELF_OBSTRUCTED =
        TEST_FUNCTIONS.register("set_block_self_obstructed", () -> SetBlockGameTests::selfObstructionBlocksPlacement);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SET_BLOCK_OTHER_ENTITY_OBSTRUCTED =
        TEST_FUNCTIONS.register("set_block_other_entity_obstructed", () -> SetBlockGameTests::otherEntityObstructionBlocksPlacement);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> SET_BLOCK_COLLISIONLESS_ALLOWED =
        TEST_FUNCTIONS.register("set_block_collisionless_allowed", () -> SetBlockGameTests::collisionlessBlockAllowsPlacement);

    // ===== 环境 AI 策略 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> AI_DISABLED_MAINTAINED =
        TEST_FUNCTIONS.register("env_disable_vanilla_ai_maintained", () -> EnvAiGameTests::disableVanillaAiIsMaintained);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> AI_DISABLED_ONLY_BETWEEN_ACTIONS =
        TEST_FUNCTIONS.register("env_disable_vanilla_ai_only_between_actions", () -> EnvAiGameTests::disableVanillaAiOnlyBetweenActions);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> AI_ENABLED_USES_CONTROLLER_POLICY =
        TEST_FUNCTIONS.register("env_enable_vanilla_ai_uses_controller_policy", () -> EnvAiGameTests::enabledVanillaAiUsesControllerPolicy);

    // ===== Agent 死亡生命周期 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> IDLE_DEATH_RETURNS_TERMINATED_STEP =
        TEST_FUNCTIONS.register("agent_idle_death_returns_terminated_step", () -> AgentDeathGameTests::idleDeathReturnsTerminatedStep);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> RUNNING_ACTION_DEATH_COMPLETES_CURRENT_STEP =
        TEST_FUNCTIONS.register("agent_running_action_death_completes_current_step", () -> AgentDeathGameTests::runningActionDeathCompletesCurrentStep);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> RUNNING_MOVE_TO_DEATH_COMPLETES_CURRENT_STEP =
        TEST_FUNCTIONS.register("agent_running_move_to_death_completes_current_step", () -> AgentDeathGameTests::runningMoveToDeathCompletesCurrentStep);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> ENV_TERMINATION_INDEPENDENT_FROM_ACTION_STATE =
        TEST_FUNCTIONS.register("env_termination_independent_from_action_state", () -> AgentDeathGameTests::envTerminationIsIndependentFromActionState);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> REMOVED_ENTITY_FAILS_IN_ACTION_LAYER =
        TEST_FUNCTIONS.register("removed_entity_fails_in_action_layer", () -> AgentDeathGameTests::removedEntityFailsInActionLayer);

    // ===== 跳跃 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> JUMP_APPLIED =
        TEST_FUNCTIONS.register("jump_applied_and_executed", () -> JumpGameTests::jumpAppliedAndExecuted);

    // ===== 注视目标 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LOOK_AT_ENTITY_EYES =
        TEST_FUNCTIONS.register("look_at_living_entity_eyes", () -> LookAtGameTests::lookAtLivingEntityEyes);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LOOK_AT_ITEM_CENTER =
        TEST_FUNCTIONS.register("look_at_item_center", () -> LookAtGameTests::lookAtItemCenter);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LOOK_AT_BLOCK_CENTER =
        TEST_FUNCTIONS.register("look_at_block_center", () -> LookAtGameTests::lookAtBlockCenter);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> LOOK_AT_AIR_BLOCK_CENTER =
        TEST_FUNCTIONS.register("look_at_air_block_center", () -> LookAtGameTests::lookAtAirBlockCenter);

    // ===== 掉落物观测与拾取 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PICKUP_WITHIN_REACH_COLLECTS =
        TEST_FUNCTIONS.register("pickup_within_reach_collects", () -> PickupGameTests::pickupWithinReachCollects);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> PICKUP_UNKNOWN_ENTITY_FAILS =
        TEST_FUNCTIONS.register("pickup_unknown_entity_fails", () -> PickupGameTests::pickupUnknownEntityFails);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> NEARBY_ITEMS_OBSERVATION_LISTS_ITEM =
        TEST_FUNCTIONS.register("nearby_items_observation_lists_item", () -> PickupGameTests::nearbyItemsObservationListsItem);

    // ===== 丢出物品 =====
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DROP_REQUESTED_COUNT_FORWARD =
        TEST_FUNCTIONS.register("drop_item_requested_count_forward", () -> DropItemGameTests::dropRequestedCountForward);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DROP_ZERO_COUNT_WHOLE_STACK =
        TEST_FUNCTIONS.register("drop_item_zero_count_whole_stack", () -> DropItemGameTests::zeroCountDropsWholeStack);
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> DROP_INVALID_SOURCE_FAILS =
        TEST_FUNCTIONS.register("drop_item_invalid_source_fails", () -> DropItemGameTests::invalidSourceFails);

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
    public static final DeferredHolder<Consumer<GameTestHelper>, Consumer<GameTestHelper>> CLEAR_ALL_ITEMS_REMOVES =
        TEST_FUNCTIONS.register("regression_clear_all_items_removes_without_drop", () -> MenuRegressionGameTests::clearAllItemsRemovesWithoutDrop);

    // 使用物品的独立场景，使用专用空结构与统一测试时限。
    static {
        TEST_FUNCTIONS.register("use_item_bone_meal", () -> UseItemGameTests::boneMeal);
        TEST_FUNCTIONS.register("use_item_block_face_durability", () -> UseItemGameTests::blockFaceAndDurability);
        TEST_FUNCTIONS.register("use_item_entity_no_fallback", () -> UseItemGameTests::entityAndNoFallback);
        TEST_FUNCTIONS.register("use_item_throw_container_menu", () -> UseItemGameTests::throwFromContainer);
        TEST_FUNCTIONS.register("use_item_food_waits", () -> UseItemGameTests::foodWaits);
        TEST_FUNCTIONS.register("use_item_potion_isolation", () -> UseItemGameTests::potionAndIsolation);
        TEST_FUNCTIONS.register("use_item_remainder_zero_duration", () -> UseItemGameTests::remainderAndZeroDuration);
        TEST_FUNCTIONS.register("use_item_interruption", () -> UseItemGameTests::interruption);
        TEST_FUNCTIONS.register("use_item_timeout", () -> UseItemGameTests::timeout);
        TEST_FUNCTIONS.register("use_item_invalid_inputs", () -> UseItemGameTests::invalidInputs);
        TEST_FUNCTIONS.register("use_item_reach_obstruction", () -> UseItemGameTests::reachAndObstruction);
        TEST_FUNCTIONS.register("use_item_composite_failure", () -> UseItemGameTests::compositeFailure);
        TEST_FUNCTIONS.register("use_item_death", () -> UseItemGameTests::death);
        TEST_FUNCTIONS.register("use_item_equipment", () -> UseItemGameTests::equipment);
        TEST_FUNCTIONS.register("use_item_runtime_reset", () -> UseItemGameTests::runtimeReset);
        TEST_FUNCTIONS.register("use_item_runtime_close", () -> UseItemGameTests::runtimeClose);
        TEST_FUNCTIONS.register("use_item_reset_menu_identity", () -> UseItemGameTests::resetMenuIdentity);
    }

    /** 禁止实例化测试注册入口。 */
    private GymCraftGameTests() {
    }

    /** 在 RegisterGameTestsEvent 中装配全部测试实例，按场景选择空结构边界。 */
    public static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
            Identifier.fromNamespaceAndPath(GymCraft.MODID, "default")
        );
        for (var holder : TEST_FUNCTIONS.getEntries()) {
            event.registerTest(
                holder.getId(),
                new FunctionGameTestInstance(
                    holder.getKey(),
                    new TestData<>(environment,
                        holder.getId().getPath().startsWith("use_item_") ? USE_ITEM_STRUCTURE : EMPTY_STRUCTURE,
                        MAX_TICKS, 0, true)
                )
            );
        }
    }
}
