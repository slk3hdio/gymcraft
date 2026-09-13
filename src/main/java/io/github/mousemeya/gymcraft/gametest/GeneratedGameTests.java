package io.github.mousemeya.gymcraft.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import io.github.mousemeya.gymcraft.GymCraft;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GymCraft GameTest 注册入口（1.21.1 注解方式，由 26.1 的 TEST_FUNCTION 注册表生成）。
 * <p>
 * 1.21.1 的 NeoForge 仅支持 {@code @GameTest} 注解注册，无 26.1 的
 * {@code FunctionGameTestInstance}/{@code TestData} 程序化装配。本类按既有
 * 测试清单为每个测试生成同名的 {@code @GameTest} 委托方法，保持测试 ID
 * （{@code gymcraft:<name>}）与原注册表一致；时限映射也与原 {@code register()} 相同。
 * 全部测试复用 {@code gymcraft:use_item_empty} 空结构（8×6×8，1.21.1 无原版
 * {@code minecraft:empty} 模板），测试逻辑自行放置方块与实体。
 * 测试类放在 main 源集：仅 GameTest 启用时被扫描，对生产环境无副作用。
 * </p>
 */
@GameTestHolder(GymCraft.MODID)
@PrefixGameTestTemplate(false)
public final class GeneratedGameTests {
    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void menu_batch_moves_order(GameTestHelper helper) {
        MenuSessionGameTests.batchMovesInOrder(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void menu_batch_moves_failure(GameTestHelper helper) {
        MenuSessionGameTests.batchMoveStopsOnFailure(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void menu_batch_moves_stale(GameTestHelper helper) {
        MenuSessionGameTests.batchMoveChecksAllBaselines(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void menu_session_id_stable(GameTestHelper helper) {
        MenuSessionGameTests.sessionIdStableAcrossObservations(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void menu_reopen_new_id_old_id_rejected(GameTestHelper helper) {
        MenuSessionGameTests.reopenGetsNewIdAndOldIdRejected(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void menu_stale_source_blocks_move(GameTestHelper helper) {
        MenuSessionGameTests.staleSourceBlocksMove(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void menu_unrelated_slot_change_allows_move(GameTestHelper helper) {
        MenuSessionGameTests.unrelatedSlotChangeAllowsMove(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void menu_stale_state_allows_close(GameTestHelper helper) {
        MenuSessionGameTests.staleStateAllowsClose(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void inventory_equipment_slot_ids_stable(GameTestHelper helper) {
        MenuInventoryGameTests.equipmentSlotIdsStable(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void inventory_villager_carrier_slot_ids(GameTestHelper helper) {
        MenuInventoryGameTests.villagerCarrierSlotIds(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void inventory_horse_body_armor_slot(GameTestHelper helper) {
        MenuInventoryGameTests.horseBodyArmorReusesEquipmentSlot(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void inventory_chest_menu_slot_allocation(GameTestHelper helper) {
        MenuInventoryGameTests.chestMenuSlotIdAllocation(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void inventory_self_menu_shape(GameTestHelper helper) {
        MenuInventoryGameTests.selfMenuShape(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void inventory_self_menu_crafting(GameTestHelper helper) {
        MenuInventoryGameTests.selfMenuCraftingWithBackpack(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void inventory_crafting_table_slot_categories(GameTestHelper helper) {
        MenuInventoryGameTests.craftingTableSlotCategories(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void inventory_slot_ids_invalid_after_close(GameTestHelper helper) {
        MenuInventoryGameTests.slotIdsInvalidAfterClose(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void attachment_standalone_persistence(GameTestHelper helper) {
        MobAttachmentGameTests.standaloneServiceAndPersistence(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void attachment_scope_visibility(GameTestHelper helper) {
        MobAttachmentGameTests.accessScopeHidesWithoutDeleting(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void attachment_env_backpack_selection(GameTestHelper helper) {
        MobAttachmentGameTests.environmentsChooseBackpackAccess(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void attachment_backpack_menu_bridge(GameTestHelper helper) {
        MobAttachmentGameTests.backpackMenuBridge(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void attachment_backpack_death_drops(GameTestHelper helper) {
        MobAttachmentGameTests.backpackDropsWithoutEnvironment(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void attachment_backpack_reset_scope(GameTestHelper helper) {
        MobAttachmentGameTests.resetRestoresBackpackAndScope(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_backpack_transaction(GameTestHelper helper) {
        MobAttachmentGameTests.backpackUseItemTransaction(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void fake_player_actor_modes(GameTestHelper helper) {
        FakePlayerBridgeGameTests.actorModesAndIsolation(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void fake_player_inventory_transaction(GameTestHelper helper) {
        FakePlayerBridgeGameTests.inventoryTransactionCommitOnce(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void fake_player_menu_isolation(GameTestHelper helper) {
        FakePlayerBridgeGameTests.activeMenuIsolation(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_empty_source_fails(GameTestHelper helper) {
        MenuMoveGameTests.emptySourceFails(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_illegal_target_fails(GameTestHelper helper) {
        MenuMoveGameTests.illegalTargetFails(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_count_truncated_to_source(GameTestHelper helper) {
        MenuMoveGameTests.countTruncatedToSource(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_partial_when_target_full(GameTestHelper helper) {
        MenuMoveGameTests.partialMoveWhenTargetFull(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_different_items_not_merged(GameTestHelper helper) {
        MenuMoveGameTests.differentItemsNotMerged(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_carried_always_empty(GameTestHelper helper) {
        MenuMoveGameTests.carriedAlwaysEmpty(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_repeat_moves_multiple_times(GameTestHelper helper) {
        MenuMoveGameTests.repeatMovesMultipleTimes(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_repeat_stops_when_source_empty(GameTestHelper helper) {
        MenuMoveGameTests.repeatStopsWhenSourceEmpty(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void move_repeat_takes_consecutive_crafting_outputs(GameTestHelper helper) {
        MenuMoveGameTests.repeatTakesConsecutiveCraftingOutputs(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void lifecycle_double_chest_merged(GameTestHelper helper) {
        MenuLifecycleGameTests.doubleChestMerged(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void lifecycle_blocked_chest_fails(GameTestHelper helper) {
        MenuLifecycleGameTests.blockedChestFails(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void lifecycle_target_destroyed_auto_closes(GameTestHelper helper) {
        MenuLifecycleGameTests.targetDestroyedAutoCloses(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void lifecycle_close_exactly_once(GameTestHelper helper) {
        MenuLifecycleGameTests.closeExactlyOnce(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void lifecycle_entity_events_close_sessions(GameTestHelper helper) {
        MenuLifecycleGameTests.entityLifecycleEventsCloseSessions(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void lifecycle_candidate_failure_keeps_old(GameTestHelper helper) {
        MenuLifecycleGameTests.candidateFailureKeepsOld(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void lifecycle_reopen_replaces_session(GameTestHelper helper) {
        MenuLifecycleGameTests.reopenReplacesSession(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void lifecycle_multi_agent_isolation(GameTestHelper helper) {
        MenuLifecycleGameTests.multiAgentIsolation(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void aggregate_declaration_order_and_isolation(GameTestHelper helper) {
        ActionAggregateGameTests.aggregateDeclarationOrder(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void action_timeout_fails(GameTestHelper helper) {
        ActionTimeoutGameTests.actionTimeoutFails(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void set_block_self_obstructed(GameTestHelper helper) {
        SetBlockGameTests.selfObstructionBlocksPlacement(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void set_block_other_entity_obstructed(GameTestHelper helper) {
        SetBlockGameTests.otherEntityObstructionBlocksPlacement(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void set_block_collisionless_allowed(GameTestHelper helper) {
        SetBlockGameTests.collisionlessBlockAllowsPlacement(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void set_block_inventory_fallback(GameTestHelper helper) {
        SetBlockGameTests.inventoryFallbackSwapsToMainHand(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void set_block_missing_item(GameTestHelper helper) {
        SetBlockGameTests.missingBlockItemFails(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void interesting_blocks_update(GameTestHelper helper) {
        InterestingBlocksGameTests.updateIsIdempotentAndRemoves(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void interesting_blocks_atomic(GameTestHelper helper) {
        InterestingBlocksGameTests.invalidAndConflictingUpdatesAreAtomic(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void interesting_blocks_lifecycle(GameTestHelper helper) {
        InterestingBlocksGameTests.attachmentIsIsolatedAndClearedByReset(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void interesting_blocks_observation(GameTestHelper helper) {
        InterestingBlocksGameTests.observationFiltersVisibleSurfaces(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void env_disable_vanilla_ai_maintained(GameTestHelper helper) {
        EnvAiGameTests.disableVanillaAiIsMaintained(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void env_disable_vanilla_ai_only_between_actions(GameTestHelper helper) {
        EnvAiGameTests.disableVanillaAiOnlyBetweenActions(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void env_enable_vanilla_ai_uses_controller_policy(GameTestHelper helper) {
        EnvAiGameTests.enabledVanillaAiUsesControllerPolicy(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 160)
    public static void navigation_preempts_move_goal(GameTestHelper helper) {
        NavigationReliabilityGameTests.runningMoveGoalIsPreemptedBeforeNewPath(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 160)
    public static void navigation_precise_repath(GameTestHelper helper) {
        NavigationReliabilityGameTests.preciseMoveRepathsAfterExternalClear(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 160)
    public static void navigation_moving_pickup(GameTestHelper helper) {
        NavigationReliabilityGameTests.movingItemRefreshesPickupPath(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 160)
    public static void navigation_repath_budget(GameTestHelper helper) {
        NavigationReliabilityGameTests.repathBudgetIsBounded(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void iron_mining_reset_restore(GameTestHelper helper) {
        IronMiningEnvGameTests.resetAndCloseRestoreArena(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void iron_mining_progression(GameTestHelper helper) {
        IronMiningEnvGameTests.completeToolProgression(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void iron_mining_failure_limit(GameTestHelper helper) {
        IronMiningEnvGameTests.failureAndStepLimit(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void iron_golem_warden_reset_restore(GameTestHelper helper) {
        IronGolemWardenEnvGameTests.resetAndCloseRestoreArena(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void iron_golem_warden_victory(GameTestHelper helper) {
        IronGolemWardenEnvGameTests.golemSpawnHealAndVictory(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void iron_golem_warden_failure_limit(GameTestHelper helper) {
        IronGolemWardenEnvGameTests.failureAndStepLimit(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void iron_golem_warden_invalid_pattern(GameTestHelper helper) {
        IronGolemWardenEnvGameTests.invalidPatternFailsUnrecoverably(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void iron_golem_warden_resupply(GameTestHelper helper) {
        IronGolemWardenEnvGameTests.suppliesReissuedAcrossResets(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void agent_idle_death_returns_terminated_step(GameTestHelper helper) {
        AgentDeathGameTests.idleDeathReturnsTerminatedStep(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void agent_running_action_death_completes_current_step(GameTestHelper helper) {
        AgentDeathGameTests.runningActionDeathCompletesCurrentStep(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void agent_running_move_to_death_completes_current_step(GameTestHelper helper) {
        AgentDeathGameTests.runningMoveToDeathCompletesCurrentStep(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void env_termination_independent_from_action_state(GameTestHelper helper) {
        AgentDeathGameTests.envTerminationIsIndependentFromActionState(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void removed_entity_fails_in_action_layer(GameTestHelper helper) {
        AgentDeathGameTests.removedEntityFailsInActionLayer(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void chat_observation_shows_recent_messages(GameTestHelper helper) {
        ChatObservationGameTests.chatObservationShowsRecentMessages(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void chat_send_action_broadcasts(GameTestHelper helper) {
        ChatObservationGameTests.sendChatActionBroadcastsAndObserves(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 12000)
    public static void chat_send_action_validates(GameTestHelper helper) {
        ChatObservationGameTests.sendChatActionValidatesMessages(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void jump_applied_and_executed(GameTestHelper helper) {
        JumpGameTests.jumpAppliedAndExecuted(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void look_at_living_entity_eyes(GameTestHelper helper) {
        LookAtGameTests.lookAtLivingEntityEyes(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void look_at_item_center(GameTestHelper helper) {
        LookAtGameTests.lookAtItemCenter(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void look_at_block_center(GameTestHelper helper) {
        LookAtGameTests.lookAtBlockCenter(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void look_at_air_block_center(GameTestHelper helper) {
        LookAtGameTests.lookAtAirBlockCenter(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void pickup_within_reach_collects(GameTestHelper helper) {
        PickupGameTests.pickupWithinReachCollects(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void pickup_unknown_entity_fails(GameTestHelper helper) {
        PickupGameTests.pickupUnknownEntityFails(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void nearby_items_observation_lists_item(GameTestHelper helper) {
        PickupGameTests.nearbyItemsObservationListsItem(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void drop_item_requested_count_forward(GameTestHelper helper) {
        DropItemGameTests.dropRequestedCountForward(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void drop_item_zero_count_whole_stack(GameTestHelper helper) {
        DropItemGameTests.zeroCountDropsWholeStack(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void drop_item_invalid_source_fails(GameTestHelper helper) {
        DropItemGameTests.invalidSourceFails(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void button_lectern_page_bounds(GameTestHelper helper) {
        MenuButtonGameTests.lecternPageBounds(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void button_lectern_take_book(GameTestHelper helper) {
        MenuButtonGameTests.lecternTakeBook(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void button_unadapted_menu_fails(GameTestHelper helper) {
        MenuButtonGameTests.unadaptedMenuButtonFails(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void button_stonecutter_select_recipe(GameTestHelper helper) {
        MenuButtonGameTests.stonecutterSelectRecipe(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void button_loom_select_pattern(GameTestHelper helper) {
        MenuButtonGameTests.loomSelectPattern(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void regression_self_menu_open_returns_completed(GameTestHelper helper) {
        MenuRegressionGameTests.selfMenuOpenReturnsCompleted(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void regression_block_open_syncs_agent_player_position(GameTestHelper helper) {
        MenuRegressionGameTests.blockOpenSyncsAgentPlayerPosition(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void regression_snapshot_preserves_equipment(GameTestHelper helper) {
        MenuRegressionGameTests.snapshotPreservesEquipment(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void regression_lectern_book_move_conserves_item(GameTestHelper helper) {
        MenuRegressionGameTests.lecternBookMoveConservesItem(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void regression_drop_all_items_conserves_carried(GameTestHelper helper) {
        MenuRegressionGameTests.dropAllItemsConservesCarriedItems(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void regression_clear_all_items_removes_without_drop(GameTestHelper helper) {
        MenuRegressionGameTests.clearAllItemsRemovesWithoutDrop(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_bone_meal(GameTestHelper helper) {
        UseItemGameTests.boneMeal(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_composter(GameTestHelper helper) {
        UseItemGameTests.composter(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_block_face_durability(GameTestHelper helper) {
        UseItemGameTests.blockFaceAndDurability(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_entity_no_fallback(GameTestHelper helper) {
        UseItemGameTests.entityAndNoFallback(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_cure_zombie_villager(GameTestHelper helper) {
        UseItemGameTests.cureZombieVillager(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_heal_iron_golem(GameTestHelper helper) {
        UseItemGameTests.healIronGolem(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_throw_at_entity(GameTestHelper helper) {
        UseItemGameTests.throwAtEntity(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_throw_container_menu(GameTestHelper helper) {
        UseItemGameTests.throwFromContainer(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_food_waits(GameTestHelper helper) {
        UseItemGameTests.foodWaits(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_potion_isolation(GameTestHelper helper) {
        UseItemGameTests.potionAndIsolation(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_remainder_zero_duration(GameTestHelper helper) {
        UseItemGameTests.remainderAndZeroDuration(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_interruption(GameTestHelper helper) {
        UseItemGameTests.interruption(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_timeout(GameTestHelper helper) {
        UseItemGameTests.timeout(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_invalid_inputs(GameTestHelper helper) {
        UseItemGameTests.invalidInputs(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_reach_obstruction(GameTestHelper helper) {
        UseItemGameTests.reachAndObstruction(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_composite_failure(GameTestHelper helper) {
        UseItemGameTests.compositeFailure(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_death(GameTestHelper helper) {
        UseItemGameTests.death(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_equipment(GameTestHelper helper) {
        UseItemGameTests.equipment(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_runtime_reset(GameTestHelper helper) {
        UseItemGameTests.runtimeReset(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_runtime_close(GameTestHelper helper) {
        UseItemGameTests.runtimeClose(helper);
    }

    @GameTest(template = "use_item_empty", timeoutTicks = 100)
    public static void use_item_reset_menu_identity(GameTestHelper helper) {
        UseItemGameTests.resetMenuIdentity(helper);
    }
    /** 禁止实例化测试注册类。 */
    private GeneratedGameTests() {
    }
}
