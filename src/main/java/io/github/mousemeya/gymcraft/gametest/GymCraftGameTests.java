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
import net.neoforged.neoforge.registries.DeferredRegister;

import io.github.mousemeya.gymcraft.GymCraft;

/**
 * GymCraft GameTest 注册入口（NeoForge 26.1 无数据包方式）。
 * <p>
 * 测试函数经 {@code TEST_FUNCTION} 注册表注册，测试实例在 {@link RegisterGameTestsEvent}
 * （模组总线，仅 GameTest 启用时触发）中统一装配：环境为空环境（不修改世界），
 * 结构统一采用覆盖完整场景的 8×6×8 空气盒——GameTest 每次运行随机选址，只有结构
 * 边界覆盖全部实体，其区块才会被框架强加载（实体 tick、env reset/step 依赖）。
 * 测试类放在 main 源集：注册仅在 GameTest 启用时生效，对生产环境无副作用。
 * </p>
 * <p>
 * 只保留核心用例：环境契约（终止语义/动作批次/AI 控制）、状态基线与场景存档、
 * 任务环境端到端，以及每个动作/观测/菜单组件族的代表性用例。粒度化的边界矩阵
 * 与历史回归用例已裁剪，需要时可从 git 历史恢复。
 * </p>
 */
public final class GymCraftGameTests {
    public static final DeferredRegister<Consumer<GameTestHelper>> TEST_FUNCTIONS = DeferredRegister.create(
        BuiltInRegistries.TEST_FUNCTION,
        GymCraft.MODID
    );

    /** 覆盖完整场景的 8×6×8 空结构（纯空气）：所有用例统一使用，保证实体所在区块被框架加载。 */
    private static final Identifier USE_ITEM_STRUCTURE = Identifier.fromNamespaceAndPath(GymCraft.MODID, "use_item_empty");
    /** 同步测试一个 tick 内完成，100 tick 超时足够冗余。 */
    private static final int MAX_TICKS = 100;

    static {
        // ===== 环境契约：终止语义与实体失效 =====
        TEST_FUNCTIONS.register("agent_idle_death_returns_terminated_step", () -> AgentDeathGameTests::idleDeathReturnsTerminatedStep);
        TEST_FUNCTIONS.register("agent_running_action_death_completes_current_step", () -> AgentDeathGameTests::runningActionDeathCompletesCurrentStep);
        TEST_FUNCTIONS.register("env_termination_independent_from_action_state", () -> AgentDeathGameTests::envTerminationIsIndependentFromActionState);
        TEST_FUNCTIONS.register("removed_entity_fails_in_action_layer", () -> AgentDeathGameTests::removedEntityFailsInActionLayer);

        // ===== 环境契约：动作批次语义 =====
        TEST_FUNCTIONS.register("action_timeout_fails", () -> ActionTimeoutGameTests::actionTimeoutFails);
        TEST_FUNCTIONS.register("action_batch_serial", () -> ActionBatchGameTests::repeatedActionsRunSerially);
        TEST_FUNCTIONS.register("action_batch_stop_and_empty", () -> ActionBatchGameTests::failureStopsAndEmptyBatchNoops);

        // ===== 环境契约：原版 AI 控制 =====
        TEST_FUNCTIONS.register("env_disable_vanilla_ai_maintained", () -> EnvAiGameTests::disableVanillaAiIsMaintained);
        TEST_FUNCTIONS.register("env_enable_vanilla_ai_uses_controller_policy", () -> EnvAiGameTests::enabledVanillaAiUsesControllerPolicy);

        // ===== 环境状态基线（docs/environment-state-system.md） =====
        TEST_FUNCTIONS.register("state_block_rollback", () -> EnvStateGameTests::blockRollbackAcrossResets);
        TEST_FUNCTIONS.register("state_entity_rollback", () -> EnvStateGameTests::entityRollbackAcrossResets);
        TEST_FUNCTIONS.register("state_item_cleanup", () -> EnvStateGameTests::itemEntitiesClearedAcrossResets);
        TEST_FUNCTIONS.register("state_warden_no_dig", () -> EnvStateGameTests::wardenBaselineResetKeepsSurfaced);

        // ===== 场景存档（docs/environment-scenarios.md） =====
        TEST_FUNCTIONS.register("scenario_parkour_round_trip", () -> ScenarioGameTests::parkourSaveSpawnRoundTrip);
        TEST_FUNCTIONS.register("scenario_warden_relocation", () -> ScenarioGameTests::wardenRelocation);
        TEST_FUNCTIONS.register("scenario_mount_auto_resets", () -> ScenarioGameTests::mountAutoResets);
        TEST_FUNCTIONS.register("scenario_immediate_capture", () -> ScenarioGameTests::immediateCaptureRoundTrip);
        TEST_FUNCTIONS.register("scenario_store_edges", () -> ScenarioGameTests::storeEdgeCases);
        TEST_FUNCTIONS.register("scenario_codec_mca", () -> ScenarioGameTests::codecMcaRoundTrip);
        TEST_FUNCTIONS.register("scenario_region_restore", () -> ScenarioGameTests::regionRestore);
        TEST_FUNCTIONS.register("scenario_world_bounds", () -> ScenarioGameTests::worldBoundsClipping);
        TEST_FUNCTIONS.register("scenario_bundled_isolation", () -> ScenarioGameTests::bundledIsolation);
        TEST_FUNCTIONS.register("scenario_attach_existing_mob", () -> ScenarioGameTests::attachExistingMob);
        TEST_FUNCTIONS.register("scenario_attach_scope_full", () -> ScenarioGameTests::attachScopeRestoresBlocksAndEntities);
        TEST_FUNCTIONS.register("scenario_attach_scope_entities_only", () -> ScenarioGameTests::attachScopeEntitiesOnlyLeavesBlocks);
        TEST_FUNCTIONS.register("scenario_attach_scope_full_height", () -> ScenarioGameTests::attachScopeVerticalClampsToWorldHeight);
        TEST_FUNCTIONS.register("scenario_entities_only_codec", () -> ScenarioGameTests::entitiesOnlyCodecRoundTrip);

        // ===== 任务环境端到端 =====
        TEST_FUNCTIONS.register("iron_mining_reset_restore", () -> IronMiningEnvGameTests::resetMaterializesScenario);
        TEST_FUNCTIONS.register("iron_mining_progression", () -> IronMiningEnvGameTests::completeToolProgression);
        TEST_FUNCTIONS.register("iron_golem_warden_reset_restore", () -> IronGolemWardenEnvGameTests::resetMaterializesScenario);
        TEST_FUNCTIONS.register("iron_golem_warden_victory", () -> IronGolemWardenEnvGameTests::golemSpawnHealAndVictory);

        // ===== PlayerSim 受控实体 =====
        TEST_FUNCTIONS.register("player_sim_profile", () -> PlayerSimGameTests::playerSimHasPlayerLikeProfile);
        TEST_FUNCTIONS.register("player_sim_env_agent", () -> PlayerSimGameTests::playerSimWorksAsEnvAgent);
        TEST_FUNCTIONS.register("player_sim_attack_target", () -> PlayerSimGameTests::playerSimPursuesConfiguredAttackTarget);

        // ===== 动作组件：每族一个代表性用例 =====
        TEST_FUNCTIONS.register("break_block_tool_swap", () -> BreakBlockGameTests::toolSwapsIntoMainHand);
        TEST_FUNCTIONS.register("set_block_self_obstructed", () -> SetBlockGameTests::selfObstructionBlocksPlacement);
        TEST_FUNCTIONS.register("jump_applied_and_executed", () -> JumpGameTests::jumpAppliedAndExecuted);
        TEST_FUNCTIONS.register("look_at_living_entity_eyes", () -> LookAtGameTests::lookAtLivingEntityEyes);
        TEST_FUNCTIONS.register("navigation_preempts_move_goal", () -> NavigationReliabilityGameTests::runningMoveGoalIsPreemptedBeforeNewPath);
        TEST_FUNCTIONS.register("swim_water_crossing", () -> NavigationWaterGameTests::crossingCompletes);
        TEST_FUNCTIONS.register("pickup_within_reach_collects", () -> PickupGameTests::pickupWithinReachCollects);
        TEST_FUNCTIONS.register("drop_item_requested_count_forward", () -> DropItemGameTests::dropRequestedCountForward);
        TEST_FUNCTIONS.register("chat_send_action_broadcasts", () -> ChatObservationGameTests::sendChatActionBroadcastsAndObserves);
        TEST_FUNCTIONS.register("use_item_bone_meal", () -> UseItemGameTests::boneMeal);
        TEST_FUNCTIONS.register("use_item_cure_zombie_villager", () -> UseItemGameTests::cureZombieVillager);
        TEST_FUNCTIONS.register("action_feedback_attack_once", () -> ActionFeedbackGameTests::attackOnceReportsSpecificFailures);

        // ===== 观测组件：每族一个代表性用例 =====
        TEST_FUNCTIONS.register("observation_header_batch_feedback", () -> ObservationFeedbackGameTests::headerAggregatesCompletedBatch);
        TEST_FUNCTIONS.register("observation_self_main_hand_item", () -> ObservationFeedbackGameTests::selfObservationReportsMainHandItem);
        TEST_FUNCTIONS.register("chat_observation_shows_recent_messages", () -> ChatObservationGameTests::chatObservationShowsRecentMessages);
        TEST_FUNCTIONS.register("damage_log_incremental", () -> DamageLogGameTests::healthLogObservesDamageBetweenObservations);
        TEST_FUNCTIONS.register("world_state_biome_and_structure", () -> ChatObservationGameTests::worldStateReportsBiomeAndStructure);
        TEST_FUNCTIONS.register("world_state_structure_inside_piece", () -> ChatObservationGameTests::worldStateReportsStructureInsidePiece);
        TEST_FUNCTIONS.register("nearby_items_observation_lists_item", () -> PickupGameTests::nearbyItemsObservationListsItem);
        TEST_FUNCTIONS.register("interesting_blocks_update", () -> InterestingBlocksGameTests::updateIsIdempotentAndRemoves);

        // ===== 菜单子系统：会话/槽位/按钮/桥接代表用例 =====
        TEST_FUNCTIONS.register("menu_session_id_stable", () -> MenuSessionGameTests::sessionIdStableAcrossObservations);
        TEST_FUNCTIONS.register("menu_reopen_new_id_old_id_rejected", () -> MenuSessionGameTests::reopenGetsNewIdAndOldIdRejected);
        TEST_FUNCTIONS.register("menu_batch_moves_order", () -> MenuSessionGameTests::batchMovesInOrder);
        TEST_FUNCTIONS.register("inventory_chest_menu_slot_allocation", () -> MenuInventoryGameTests::chestMenuSlotIdAllocation);
        TEST_FUNCTIONS.register("lifecycle_target_destroyed_auto_closes", () -> MenuLifecycleGameTests::targetDestroyedAutoCloses);
        TEST_FUNCTIONS.register("lifecycle_multi_agent_isolation", () -> MenuLifecycleGameTests::multiAgentIsolation);
        TEST_FUNCTIONS.register("button_stonecutter_select_recipe", () -> MenuButtonGameTests::stonecutterSelectRecipe);
        TEST_FUNCTIONS.register("fake_player_menu_isolation", () -> FakePlayerBridgeGameTests::activeMenuIsolation);
        // 混入咽喉守卫：容器开盖计数跨 recheck 存活（ContainerOpenersCounterMixin）
        TEST_FUNCTIONS.register("regression_chest_lid_count_survives_recheck", () -> MenuRegressionGameTests::chestLidCountSurvivesRecheck);

        // ===== 可视化 =====
        TEST_FUNCTIONS.register("visualization_runtime", () -> VisualizationGameTests::runtime);
    }

    /** 禁止实例化测试注册入口。 */
    private GymCraftGameTests() {
    }

    /** 在 RegisterGameTestsEvent 中装配全部测试实例，统一使用完整边界的空结构。 */
    public static void register(RegisterGameTestsEvent event) {
        Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(
            Identifier.fromNamespaceAndPath(GymCraft.MODID, "default")
        );
        // 注入合成结构起点的用例独占测试环境（即独占批次）：结构起点是跨用例共享的
        // 区块状态，测试网格相邻格子间距小于一个区块，与扫描结构的用例并行时注入会
        // 落进对方的"本区块无结构起点"断言；不同批次顺序执行，注入窗口不再重叠
        Holder<TestEnvironmentDefinition<?>> structureInjectionEnvironment = event.registerEnvironment(
            Identifier.fromNamespaceAndPath(GymCraft.MODID, "structure_injection")
        );
        for (var holder : TEST_FUNCTIONS.getEntries()) {
            boolean injectsStructureStarts = holder.getId().getPath().equals("world_state_structure_inside_piece");
            event.registerTest(
                holder.getId(),
                new FunctionGameTestInstance(
                    holder.getKey(),
                    new TestData<>(injectsStructureStarts ? structureInjectionEnvironment : environment,
                        USE_ITEM_STRUCTURE,
                        holder.getId().getPath().startsWith("iron_mining_") ? 12000
                            : holder.getId().getPath().startsWith("iron_golem_warden_") ? 12000
                            : holder.getId().getPath().startsWith("state_") ? 12000
                            : holder.getId().getPath().startsWith("scenario_") ? 12000
                            : holder.getId().getPath().startsWith("chat_") ? 12000
                            // 伤害日志用例含 600ms 无敌帧等待与多次阻塞 step，放宽时限
                            : holder.getId().getPath().startsWith("damage_log_") ? 1200
                            : holder.getId().getPath().startsWith("world_state_") ? 12000
                            : holder.getId().getPath().startsWith("player_sim_env") ? 12000
                            // 追击+近战受服务器启动期卡顿影响，100 tick 偶发打满
                            : holder.getId().getPath().equals("player_sim_attack_target") ? 400
                            : holder.getId().getPath().startsWith("navigation_") ? 160
                            : holder.getId().getPath().startsWith("swim_") ? 600
                            : MAX_TICKS,
                        0,
                        true)
                )
            );
        }
    }
}
