package io.github.mousemeya.gymcraft.gametest;

import java.util.List;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentAccessScope;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachments;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoSelfState;
import io.github.mousemeya.gymcraft.gym.rpc.proto.StepResponse;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertEquals;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;
import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.spawnAgent;

/** Agent 跨维度后的环境绑定回归测试。 */
public final class DimensionChangeGameTests {
    private DimensionChangeGameTests() {
    }

    /**
     * 原版跨维度传送会创建同 UUID 的新 Mob；验证运行时改绑新实例后仍能在目标维度
     * 完成动作与观测，且环境注册和附件访问作用域均保持有效。
     *
     * @param helper GameTest 辅助对象
     */
    public static void stepAfterDimensionChange(GameTestHelper helper) {
        Mob original = spawnAgent(helper, EntityType.ZOMBIE, new BlockPos(2, 1, 2));
        UUID agentUuid = original.getUUID();
        McEnv env = EnvManager.attach("gymcraft:simple_mob", original);
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        assertTrue(helper, nether != null, "Nether dimension is unavailable");
        Entity teleported = original.teleport(new TeleportTransition(
            nether, new Vec3(0.5, 70.0, 0.5), Vec3.ZERO, 0.0F, 0.0F,
            TeleportTransition.DO_NOTHING));
        assertTrue(helper, teleported instanceof Mob, "Cross-dimension teleport did not create a Mob");
        Mob transferred = (Mob) teleported;
        assertTrue(helper, transferred != original, "Cross-dimension teleport reused the old entity instance");
        assertEquals(helper, agentUuid, transferred.getUUID(), "Cross-dimension teleport changed the agent UUID");
        assertEquals(helper, nether, transferred.level(), "Transferred agent dimension");
        assertTrue(helper, original.isRemoved(), "Original entity was not removed after dimension travel");

        helper.runAfterDelay(2, () -> {
            AtomicReference<StepResponse> response = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread.startVirtualThread(() -> {
                try {
                    response.set(env.step(List.of(ProtoMcAction.newBuilder()
                        .setComponentId("gymcraft:noop")
                        .setPayload(Any.pack(ProtoNoop.getDefaultInstance()))
                        .build()), 0.0F));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });

            helper.runAfterDelay(8, () -> {
                try {
                    assertTrue(helper, failure.get() == null,
                        "step after dimension change raised an exception: " + failure.get());
                    StepResponse step = response.get();
                    assertTrue(helper, step != null, "step after dimension change did not return");
                    assertEquals(helper, "COMPLETED", step.getObservation().getHeader().getLastActionStatus(),
                        "step after dimension change action status");
                    assertTrue(helper, !step.getTerminated(), "dimension change must not kill the episode");
                    assertTrue(helper, EnvManager.get(agentUuid).orElse(null) == env,
                        "dimension change unregistered the environment");
                    assertTrue(helper, MobAttachmentAccessScope.canAccess(transferred, MobAttachments.AGENT_BACKPACK),
                        "attachment access scope was not rebound after dimension change");
                    ProtoSelfState self = step.getObservation().getComponentsMap()
                        .get("gymcraft:self").unpack(ProtoSelfState.class);
                    assertEquals(helper, 0.5, self.getX(), "observation still uses the old entity x position");
                    assertEquals(helper, 0.5, self.getZ(), "observation still uses the old entity z position");
                    helper.succeed();
                } catch (InvalidProtocolBufferException error) {
                    throw new IllegalStateException("Cannot unpack self observation", error);
                } finally {
                    EnvManager.close(agentUuid);
                    transferred.discard();
                }
            });
        });
    }
}
