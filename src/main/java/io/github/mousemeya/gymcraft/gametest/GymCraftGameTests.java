package io.github.mousemeya.gymcraft.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Minecraft 1.21.1 注解式测试入口；保持与 1.3.0 核心测试集合一致，委托各组件测试。 */
@GameTestHolder("gymcraft")
@PrefixGameTestTemplate(false)
public final class GymCraftGameTests {
    /** 禁止实例化静态测试入口。 */
    private GymCraftGameTests() {}

    /** 验证 agent_idle_death_returns_terminated_step；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void agent_idle_death_returns_terminated_step(GameTestHelper helper) {
        AgentDeathGameTests.idleDeathReturnsTerminatedStep(helper);
    }

    /** 验证 agent_running_action_death_completes_current_step；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void agent_running_action_death_completes_current_step(GameTestHelper helper) {
        AgentDeathGameTests.runningActionDeathCompletesCurrentStep(helper);
    }

    /** 验证 env_termination_independent_from_action_state；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void env_termination_independent_from_action_state(GameTestHelper helper) {
        AgentDeathGameTests.envTerminationIsIndependentFromActionState(helper);
    }

    /** 验证 removed_entity_fails_in_action_layer；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void removed_entity_fails_in_action_layer(GameTestHelper helper) {
        AgentDeathGameTests.removedEntityFailsInActionLayer(helper);
    }

    /** 验证 action_timeout_fails；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void action_timeout_fails(GameTestHelper helper) {
        ActionTimeoutGameTests.actionTimeoutFails(helper);
    }

    /** 验证 action_batch_serial；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void action_batch_serial(GameTestHelper helper) {
        ActionBatchGameTests.repeatedActionsRunSerially(helper);
    }

    /** 验证 action_batch_stop_and_empty；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void action_batch_stop_and_empty(GameTestHelper helper) {
        ActionBatchGameTests.failureStopsAndEmptyBatchNoops(helper);
    }

    /** 验证 env_disable_vanilla_ai_maintained；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void env_disable_vanilla_ai_maintained(GameTestHelper helper) {
        EnvAiGameTests.disableVanillaAiIsMaintained(helper);
    }

    /** 验证 env_enable_vanilla_ai_uses_controller_policy；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void env_enable_vanilla_ai_uses_controller_policy(GameTestHelper helper) {
        EnvAiGameTests.enabledVanillaAiUsesControllerPolicy(helper);
    }

    /** 验证 component_options_apply_and_persist；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void component_options_apply_and_persist(GameTestHelper helper) {
        ComponentOptionsGameTests.componentsOptionAppliesAndPersists(helper);
    }

    /** 验证 component_options_reject_unknown；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void component_options_reject_unknown(GameTestHelper helper) {
        ComponentOptionsGameTests.componentsOptionRejectsUnknown(helper);
    }

    /** 验证 state_block_rollback；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void state_block_rollback(GameTestHelper helper) {
        EnvStateGameTests.blockRollbackAcrossResets(helper);
    }

    /** 验证 state_entity_rollback；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void state_entity_rollback(GameTestHelper helper) {
        EnvStateGameTests.entityRollbackAcrossResets(helper);
    }

    /** 验证 state_item_cleanup；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void state_item_cleanup(GameTestHelper helper) {
        EnvStateGameTests.itemEntitiesClearedAcrossResets(helper);
    }

    /** 验证 state_warden_no_dig；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void state_warden_no_dig(GameTestHelper helper) {
        EnvStateGameTests.wardenBaselineResetKeepsSurfaced(helper);
    }

    /** 验证 chunk_guard_region_forced_and_released；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void chunk_guard_region_forced_and_released(GameTestHelper helper) {
        EnvChunkGuardGameTests.attachForcesRegionChunksAndCloseReleases(helper);
    }

    /** 验证 scenario_parkour_round_trip；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_parkour_round_trip(GameTestHelper helper) {
        ScenarioGameTests.parkourSaveSpawnRoundTrip(helper);
    }

    /** 验证 scenario_warden_relocation；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_warden_relocation(GameTestHelper helper) {
        ScenarioGameTests.wardenRelocation(helper);
    }

    /** 验证 scenario_mount_auto_resets；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_mount_auto_resets(GameTestHelper helper) {
        ScenarioGameTests.mountAutoResets(helper);
    }

    /** 验证 scenario_immediate_capture；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_immediate_capture(GameTestHelper helper) {
        ScenarioGameTests.immediateCaptureRoundTrip(helper);
    }

    /** 验证 scenario_store_edges；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_store_edges(GameTestHelper helper) {
        ScenarioGameTests.storeEdgeCases(helper);
    }

    /** 验证 scenario_codec_mca；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_codec_mca(GameTestHelper helper) {
        ScenarioGameTests.codecMcaRoundTrip(helper);
    }

    /** 验证 scenario_region_restore；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_region_restore(GameTestHelper helper) {
        ScenarioGameTests.regionRestore(helper);
    }

    /** 验证 scenario_world_bounds；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_world_bounds(GameTestHelper helper) {
        ScenarioGameTests.worldBoundsClipping(helper);
    }

    /** 验证 scenario_bundled_isolation；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_bundled_isolation(GameTestHelper helper) {
        ScenarioGameTests.bundledIsolation(helper);
    }

    /** 验证 scenario_attach_existing_mob；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_attach_existing_mob(GameTestHelper helper) {
        ScenarioGameTests.attachExistingMob(helper);
    }

    /** 验证 scenario_attach_scope_full；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_attach_scope_full(GameTestHelper helper) {
        ScenarioGameTests.attachScopeRestoresBlocksAndEntities(helper);
    }

    /** 验证 scenario_attach_scope_entities_only；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_attach_scope_entities_only(GameTestHelper helper) {
        ScenarioGameTests.attachScopeEntitiesOnlyLeavesBlocks(helper);
    }

    /** 验证 scenario_attach_scope_full_height；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_attach_scope_full_height(GameTestHelper helper) {
        ScenarioGameTests.attachScopeVerticalClampsToWorldHeight(helper);
    }

    /** 验证 scenario_entities_only_codec；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void scenario_entities_only_codec(GameTestHelper helper) {
        ScenarioGameTests.entitiesOnlyCodecRoundTrip(helper);
    }

    /** 验证 iron_mining_reset_restore；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void iron_mining_reset_restore(GameTestHelper helper) {
        IronMiningEnvGameTests.resetMaterializesScenario(helper);
    }

    /** 验证 iron_mining_progression；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 60000, batch = "default")
    public static void iron_mining_progression(GameTestHelper helper) {
        IronMiningEnvGameTests.completeToolProgression(helper);
    }

    /** 验证 iron_golem_warden_reset_restore；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void iron_golem_warden_reset_restore(GameTestHelper helper) {
        IronGolemWardenEnvGameTests.resetMaterializesScenario(helper);
    }

    /** 验证 iron_golem_warden_victory；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void iron_golem_warden_victory(GameTestHelper helper) {
        IronGolemWardenEnvGameTests.golemSpawnHealAndVictory(helper);
    }

    /** 验证 mining_milestone_rewards；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void mining_milestone_rewards(GameTestHelper helper) {
        MiningEnvGameTests.milestoneRewards(helper);
    }

    /** 验证 mining_max_steps_truncation；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void mining_max_steps_truncation(GameTestHelper helper) {
        MiningEnvGameTests.maxStepsTruncation(helper);
    }

    /** 验证 player_sim_profile；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void player_sim_profile(GameTestHelper helper) {
        PlayerSimGameTests.playerSimHasPlayerLikeProfile(helper);
    }

    /** 验证 player_sim_env_agent；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void player_sim_env_agent(GameTestHelper helper) {
        PlayerSimGameTests.playerSimWorksAsEnvAgent(helper);
    }

    /** 验证 player_sim_attack_target；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 400, batch = "default")
    public static void player_sim_attack_target(GameTestHelper helper) {
        PlayerSimGameTests.playerSimPursuesConfiguredAttackTarget(helper);
    }

    /** 验证 break_block_main_hand_tool；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void break_block_main_hand_tool(GameTestHelper helper) {
        BreakBlockGameTests.minesWithMainHand(helper);
    }

    /** 验证 set_block_self_obstructed；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void set_block_self_obstructed(GameTestHelper helper) {
        SetBlockGameTests.selfObstructionBlocksPlacement(helper);
    }

    /** 验证 jump_applied_and_executed；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void jump_applied_and_executed(GameTestHelper helper) {
        JumpGameTests.jumpAppliedAndExecuted(helper);
    }

    /** 验证 look_at_living_entity_eyes；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void look_at_living_entity_eyes(GameTestHelper helper) {
        LookAtGameTests.lookAtLivingEntityEyes(helper);
    }

    /** 验证 navigation_preempts_move_goal；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 160, batch = "default")
    public static void navigation_preempts_move_goal(GameTestHelper helper) {
        NavigationReliabilityGameTests.runningMoveGoalIsPreemptedBeforeNewPath(helper);
    }

    /** 验证 navigation_zero_stop_distance；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 160, batch = "default")
    public static void navigation_zero_stop_distance(GameTestHelper helper) {
        NavigationReliabilityGameTests.zeroStopDistanceCompletes(helper);
    }

    /** 验证 navigation_swim_zero_stop_distance；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 160, batch = "default")
    public static void navigation_swim_zero_stop_distance(GameTestHelper helper) {
        NavigationReliabilityGameTests.swimZeroStopDistanceCompletes(helper);
    }

    /** 验证 navigation_circling_exhausts；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 160, batch = "default")
    public static void navigation_circling_exhausts(GameTestHelper helper) {
        NavigationReliabilityGameTests.circlingWithoutProgressExhausts(helper);
    }

    /** 验证 swim_water_crossing；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 600, batch = "default")
    public static void swim_water_crossing(GameTestHelper helper) {
        NavigationWaterGameTests.crossingCompletes(helper);
    }

    /** 验证 pickup_within_reach_collects；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void pickup_within_reach_collects(GameTestHelper helper) {
        PickupGameTests.pickupWithinReachCollects(helper);
    }

    /** 验证 drop_item_requested_count_forward；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void drop_item_requested_count_forward(GameTestHelper helper) {
        DropItemGameTests.dropRequestedCountForward(helper);
    }

    /** 验证 chat_send_action_broadcasts；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void chat_send_action_broadcasts(GameTestHelper helper) {
        ChatObservationGameTests.sendChatActionBroadcastsAndObserves(helper);
    }

    /** 验证 use_item_bone_meal；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void use_item_bone_meal(GameTestHelper helper) {
        UseItemGameTests.boneMeal(helper);
    }

    /** 验证 use_item_cure_zombie_villager；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void use_item_cure_zombie_villager(GameTestHelper helper) {
        UseItemGameTests.cureZombieVillager(helper);
    }

    /** 验证 action_feedback_attack_once；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void action_feedback_attack_once(GameTestHelper helper) {
        ActionFeedbackGameTests.attackOnceReportsSpecificFailures(helper);
    }

    /** 验证 observation_header_batch_feedback；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void observation_header_batch_feedback(GameTestHelper helper) {
        ObservationFeedbackGameTests.headerAggregatesCompletedBatch(helper);
    }

    /** 验证 observation_self_main_hand_item；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void observation_self_main_hand_item(GameTestHelper helper) {
        ObservationFeedbackGameTests.selfObservationReportsMainHandItem(helper);
    }

    /** 验证 chat_observation_shows_recent_messages；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "chat_capture")
    public static void chat_observation_shows_recent_messages(GameTestHelper helper) {
        ChatObservationGameTests.chatObservationShowsRecentMessages(helper);
    }

    /** 验证 damage_log_incremental；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 1200, batch = "default")
    public static void damage_log_incremental(GameTestHelper helper) {
        DamageLogGameTests.healthLogObservesDamageBetweenObservations(helper);
    }

    /** 验证 world_state_biome_and_structure；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "default")
    public static void world_state_biome_and_structure(GameTestHelper helper) {
        ChatObservationGameTests.worldStateReportsBiomeAndStructure(helper);
    }

    /** 验证 world_state_structure_inside_piece；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 12000, batch = "structure_injection")
    public static void world_state_structure_inside_piece(GameTestHelper helper) {
        ChatObservationGameTests.worldStateReportsStructureInsidePiece(helper);
    }

    /** 验证 nearby_items_observation_lists_item；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void nearby_items_observation_lists_item(GameTestHelper helper) {
        PickupGameTests.nearbyItemsObservationListsItem(helper);
    }

    /** 验证 interesting_blocks_update；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void interesting_blocks_update(GameTestHelper helper) {
        InterestingBlocksGameTests.updateIsIdempotentAndRemoves(helper);
    }

    /** 验证 nearby_blocks_scan_bounds_clamp；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void nearby_blocks_scan_bounds_clamp(GameTestHelper helper) {
        BlockScanGameTests.scanBoundsClampRegion(helper);
    }

    /** 验证 nearby_blocks_scan_prefers_agent_surroundings；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void nearby_blocks_scan_prefers_agent_surroundings(GameTestHelper helper) {
        NearbyBlocksGameTests.scanPrefersAgentSurroundings(helper);
    }

    /** 验证 menu_session_id_stable；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void menu_session_id_stable(GameTestHelper helper) {
        MenuSessionGameTests.sessionIdStableAcrossObservations(helper);
    }

    /** 验证 menu_reopen_new_id_old_id_rejected；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void menu_reopen_new_id_old_id_rejected(GameTestHelper helper) {
        MenuSessionGameTests.reopenGetsNewIdAndOldIdRejected(helper);
    }

    /** 验证 menu_batch_moves_order；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void menu_batch_moves_order(GameTestHelper helper) {
        MenuSessionGameTests.batchMovesInOrder(helper);
    }

    /** 验证 inventory_chest_menu_slot_allocation；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void inventory_chest_menu_slot_allocation(GameTestHelper helper) {
        MenuInventoryGameTests.chestMenuSlotIdAllocation(helper);
    }

    /** 验证 lifecycle_target_destroyed_auto_closes；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void lifecycle_target_destroyed_auto_closes(GameTestHelper helper) {
        MenuLifecycleGameTests.targetDestroyedAutoCloses(helper);
    }

    /** 验证 lifecycle_multi_agent_isolation；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void lifecycle_multi_agent_isolation(GameTestHelper helper) {
        MenuLifecycleGameTests.multiAgentIsolation(helper);
    }

    /** 验证 button_stonecutter_select_recipe；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void button_stonecutter_select_recipe(GameTestHelper helper) {
        MenuButtonGameTests.stonecutterSelectRecipe(helper);
    }

    /** 验证 fake_player_menu_isolation；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void fake_player_menu_isolation(GameTestHelper helper) {
        FakePlayerBridgeGameTests.activeMenuIsolation(helper);
    }

    /** 验证 regression_chest_lid_count_survives_recheck；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void regression_chest_lid_count_survives_recheck(GameTestHelper helper) {
        MenuRegressionGameTests.chestLidCountSurvivesRecheck(helper);
    }

    /** 验证 debug_count_blocks_by_tag；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void debug_count_blocks_by_tag(GameTestHelper helper) {
        DebugCommandGameTests.countBlocksByTag(helper);
    }

    /** 验证 visualization_runtime；参数 helper 为测试辅助对象，无返回值。 */
    @GameTest(template = "use_item_empty", timeoutTicks = 100, batch = "default")
    public static void visualization_runtime(GameTestHelper helper) {
        VisualizationGameTests.runtime(helper);
    }

}
