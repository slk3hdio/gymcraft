package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.ActionStatus;
import io.github.mousemeya.gymcraft.gym.action.component.SetAttackTargetController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.envs.SimpleMobEnv;
import io.github.mousemeya.gymcraft.gym.entity.PlayerSimEntity;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoSelfState;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;
import io.github.mousemeya.gymcraft.registry.ModEntities;

/**
 * 玩家模拟实体（{@code gymcraft:player_sim}）测试 —— 验证玩家风格档案
 * （生命/体型/交互距离/不自然消失）与作为环境受控 Agent 的可用性。
 */
public final class PlayerSimGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private PlayerSimGameTests() {
    }

    /**
     * 验证实体具备玩家风格属性：20 生命、0.6×1.8 体型、玩家交互距离，
     * 且不会因距离自然消失。
     *
     * @param helper GameTest 辅助对象
     */
    public static void playerSimHasPlayerLikeProfile(GameTestHelper helper) {
        PlayerSimEntity mob = MenuGameTestSupport.spawnAgent(helper, ModEntities.PLAYER_SIM.get(), new BlockPos(2, 1, 2));
        // getMaxHealth 返回 float，统一转 double 比较避免装箱类型不一致
        assertEquals(helper, 20.0, (double) mob.getMaxHealth(), "player_sim max health should match player");
        var dimensions = mob.getDimensions(mob.getPose());
        assertEquals(helper, 0.6F, dimensions.width(), "player_sim width should match player");
        assertEquals(helper, 1.8F, dimensions.height(), "player_sim height should match player");
        assertEquals(helper, 4.5, mob.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE),
            "player_sim block interaction range should match player");
        assertEquals(helper, 3.0, mob.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE),
            "player_sim entity interaction range should match player");
        assertTrue(helper, !mob.removeWhenFarAway(1024.0),
            "player_sim must never despawn by distance (like players)");
        helper.succeed();
    }

    /**
     * 验证玩家模拟实体可作为环境受控 Agent：创建 SimpleMobEnv、reset 后执行
     * noop step，self 观测应报告实体类型 {@code gymcraft:player_sim} 且存活。
     *
     * @param helper GameTest 辅助对象
     */
    public static void playerSimWorksAsEnvAgent(GameTestHelper helper) {
        PlayerSimEntity mob = MenuGameTestSupport.spawnAgent(helper, ModEntities.PLAYER_SIM.get(), new BlockPos(2, 1, 2));
        SimpleMobEnv env = new SimpleMobEnv(
            ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "player_sim_env_test"), mob);
        AtomicReference<StepResponse> step = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CompletableFuture<Void> tested = new CompletableFuture<>();
        // 与其他 GameTest 相同：阻塞式 reset/step 必须在虚拟线程内完成
        Thread.startVirtualThread(() -> {
            try {
                env.reset(3, Map.of(AbstractMcEnv.DISABLE_VANILLA_AI_OPTION, true));
                step.set(env.step(List.of(ProtoMcAction.newBuilder()
                    .setComponentId("gymcraft:noop")
                    .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
                    .build()), 0.0F));
                tested.complete(null);
            } catch (Throwable error) {
                failure.set(error);
                tested.completeExceptionally(error);
            }
        });
        helper.runAfterDelay(100, () -> {
            try {
                assertTrue(helper, tested.isDone() && failure.get() == null,
                    "player_sim env step failed: " + failure.get());
                ProtoSelfState self = unpackSelf(step.get());
                assertTrue(helper, self != null, "self observation missing");
                assertEquals(helper, "gymcraft:player_sim", self.getEntityType(),
                    "self observation entity type mismatch");
                assertTrue(helper, self.getAlive(), "player_sim should be alive after noop step");
                helper.succeed();
            } finally {
                env.close();
            }
        });
    }

    /**
     * 验证 {@code set_attack_target} 可驱动玩家模拟实体复用原版近战 Goal，
     * 主动寻路接近目标并完成攻击。
     *
     * @param helper GameTest 辅助对象
     */
    public static void playerSimPursuesConfiguredAttackTarget(GameTestHelper helper) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 1; z <= 3; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        PlayerSimEntity mob = helper.spawn(ModEntities.PLAYER_SIM.get(), new BlockPos(1, 2, 2));
        mob.setInvulnerable(true);
        var target = helper.spawnWithNoFreeWill(EntityType.PIG, new BlockPos(7, 2, 2));
        target.setHealth(0.5F);
        double initialX = mob.getX();

        var action = ProtoSetAttackTarget.newBuilder()
            .setTargetEntityId(target.getId())
            .build();
        var result = new SetAttackTargetController(mob).apply(action);
        result.policy().applyTo(mob);
        assertEquals(helper, ActionStatus.RUNNING, result.initialState().status(),
            "set_attack_target should start tracking: " + result.initialState());

        helper.succeedWhen(() -> {
            assertTrue(helper, mob.getX() > initialX + 1.0,
                "player_sim did not pursue the configured attack target");
            assertTrue(helper, !target.isAlive(),
                "player_sim reached the target but did not execute melee attacks");
        });
    }

    /**
     * 解包 step 响应中的自身状态观测组件。
     *
     * @param response step 响应
     * @return 自身状态观测
     */
    private static ProtoSelfState unpackSelf(StepResponse response) {
        try {
            return response.getObservation().getComponentsOrThrow("gymcraft:self").unpack(ProtoSelfState.class);
        } catch (InvalidProtocolBufferException error) {
            throw new IllegalStateException("failed to unpack self observation", error);
        }
    }
}
