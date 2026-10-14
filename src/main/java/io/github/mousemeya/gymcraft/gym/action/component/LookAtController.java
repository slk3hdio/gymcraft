package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAt;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtBlockTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtEntityTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtItemTarget;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 注视目标动作组件 —— 将 Agent 的当前视线瞬时对准实体、掉落物或方块。
 * <p>
 * 普通实体目标接受已加载、存活且不是掉落物的 {@link Entity}，注视其眼睛位置；
 * 掉落物目标只接受有效的 {@link ItemEntity}，注视其包围盒中心；方块目标只需位于
 * 已加载区块，空气也可作为目标，注视对应方块坐标中心。动作会同步实体朝向、俯仰角和头部朝向，
 * 同时向 {@code LookControl} 写入同一目标，避免当前实体 tick 把视线立即复位。
 * 实现了 {@link EnvironmentRegionAware}：环境区域非空时方块目标坐标空间收紧到区域边界。
 * </p>
 * <p>
 * 该动作为瞬时动作，应用后立即返回终态；不限制注视距离，也不要求目标可见。
 * </p>
 */
public class LookAtController extends AbstractActionComponentController<ProtoLookAt>
    implements EnvironmentRegionAware {

    /**
     * 创建绑定指定 Agent 的注视动作控制器。
     *
     * @param mob 受控 Agent
     */
    public LookAtController(Mob mob) {
        super(mob);
    }

    /**
     * 返回该动作对应的 protobuf 类型。
     *
     * @return {@link ProtoLookAt} 类型
     */
    @Override
    public Class<ProtoLookAt> protoType() {
        return ProtoLookAt.class;
    }

    /** 按 oneof 分支声明嵌套约束；{@code target} oneof 必须设置分支。 */
    @Override
    public McSpace<ProtoLookAt> defaultSpace() {
        return buildSpace(null);
    }

    /** 接受环境区域（构造期一次性推送）：非空时方块目标坐标空间收紧到区域边界。 */
    @Override
    public void setEnvironmentRegion(@Nullable BoundingBox region) {
        this.setSpace(buildSpace(region));
    }

    /**
     * 构造目标空间：区域非空时 {@code block} 分支 x/y/z 收紧到区域闭区间边界，
     * 实体/掉落物分支与 oneof 要求不随区域变化。
     *
     * @param region 环境区域；null 表示无区域约束
     * @return 动作参数空间
     */
    private static McSpace<ProtoLookAt> buildSpace(@Nullable BoundingBox region) {
        return MessageSpace.<ProtoLookAt>builder(ProtoLookAt.getDescriptor())
            .message("block", blockTargetSpace(region))
            .message("entity", MessageSpace.<ProtoLookAtEntityTarget>builder(ProtoLookAtEntityTarget.getDescriptor())
                .range("entity_id", 1, Integer.MAX_VALUE)
                .build())
            .message("item", MessageSpace.<ProtoLookAtItemTarget>builder(ProtoLookAtItemTarget.getDescriptor())
                .range("entity_id", 1, Integer.MAX_VALUE)
                .build())
            .requiredOneof("target")
            .build();
    }

    /**
     * 构造方块目标分支的坐标空间：区域非空时收紧到区域边界，否则全球默认范围。
     *
     * @param region 环境区域；null 表示无区域约束
     * @return 方块目标消息空间
     */
    private static MessageSpace<ProtoLookAtBlockTarget> blockTargetSpace(@Nullable BoundingBox region) {
        var builder = MessageSpace.<ProtoLookAtBlockTarget>builder(ProtoLookAtBlockTarget.getDescriptor());
        if (region != null) {
            builder.range("x", region.minX(), region.maxX())
                .range("y", region.minY(), region.maxY())
                .range("z", region.minZ(), region.maxZ());
        } else {
            builder.range("x", -30_000_000, 30_000_000)
                .range("y", -2048, 2048)
                .range("z", -30_000_000, 30_000_000);
        }
        return builder.build();
    }

    /**
     * 解析目标并立即更新 Agent 视线。
     *
     * @param component 注视动作负载
     * @return 动作应用结果；目标无效时返回 FAILED
     */
    @Override
    public ActionApplyResult apply(ProtoLookAt component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }

        // oneof 的每一类目标分别做运行时类型和加载状态校验。
        return switch (component.getTargetCase()) {
            case ENTITY -> this.lookAtEntity(component.getEntity().getEntityId());
            case ITEM -> this.lookAtItem(component.getItem().getEntityId());
            case BLOCK -> this.lookAtBlock(new BlockPos(
                component.getBlock().getX(),
                component.getBlock().getY(),
                component.getBlock().getZ()
            ));
            case TARGET_NOT_SET -> ActionApplyResult.none(ActionState.failed("look target is not set"));
        };
    }

    /**
     * 瞬时动作始终保持已应用终态，且不会被运行时 tick。
     *
     * @param component 注视动作负载
     * @return COMPLETED 状态
     */
    @Override
    public ActionState tick(ProtoLookAt component) {
        return ActionState.completed("look at applied");
    }

    /**
     * 注视已加载的普通实体。
     *
     * @param entityId 目标网络实体 ID
     * @return 动作应用结果
     */
    private ActionApplyResult lookAtEntity(int entityId) {
        Mob mob = this.mob();
        Entity found = mob.level().getEntity(entityId);
        if (found == null) {
            return ActionApplyResult.none(ActionState.failed("entity target was not found or is not loaded", Map.of(
                "entity_id", entityId
            )));
        }
        if (found == mob) {
            return ActionApplyResult.none(ActionState.failed("agent cannot look at itself as an entity target", Map.of(
                "entity_id", entityId
            )));
        }
        if (found instanceof ItemEntity) {
            return ActionApplyResult.none(ActionState.failed("entity target is an item; use the item target variant", Map.of(
                "entity_id", entityId
            )));
        }
        if (!found.isAlive() || found.isRemoved()) {
            return ActionApplyResult.none(ActionState.failed("entity target is dead or removed", Map.of(
                "entity_id", entityId
            )));
        }
        Vec3 targetPos = EntityAnchorArgument.Anchor.EYES.apply(found);
        return this.applyLook(targetPos, Map.of(
            "target_type", "entity",
            "entity_id", entityId,
            "entity_type", BuiltInRegistries.ENTITY_TYPE.getKey(found.getType()).toString()
        ));
    }

    /**
     * 注视已加载且仍包含物品的掉落物实体。
     *
     * @param entityId 掉落物网络实体 ID
     * @return 动作应用结果
     */
    private ActionApplyResult lookAtItem(int entityId) {
        Entity found = this.mob().level().getEntity(entityId);
        if (found == null) {
            return ActionApplyResult.none(ActionState.failed("item target was not found or is not loaded", Map.of(
                "entity_id", entityId
            )));
        }
        if (!(found instanceof ItemEntity item)) {
            return ActionApplyResult.none(ActionState.failed("item target ID belongs to a non-item entity", Map.of(
                "entity_id", entityId
            )));
        }
        if (item.isRemoved() || !item.isAlive()) {
            return ActionApplyResult.none(ActionState.failed("item target is removed or no longer alive", Map.of(
                "entity_id", entityId
            )));
        }
        if (item.getItem().isEmpty()) {
            return ActionApplyResult.none(ActionState.failed("item target contains no item stack", Map.of(
                "entity_id", entityId
            )));
        }
        return this.applyLook(item.getBoundingBox().getCenter(), Map.of(
            "target_type", "item",
            "entity_id", entityId,
            "item", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString()
        ));
    }

    /**
     * 注视已加载方块坐标的中心，允许该位置为空气。
     *
     * @param pos 目标方块坐标
     * @return 动作应用结果
     */
    private ActionApplyResult lookAtBlock(BlockPos pos) {
        var level = this.mob().level();
        if (!level.isLoaded(pos)) {
            return ActionApplyResult.none(ActionState.failed("block target chunk is not loaded", Map.of(
                "pos", pos.toShortString()
            )));
        }
        var state = level.getBlockState(pos);
        return this.applyLook(Vec3.atCenterOf(pos), Map.of(
            "target_type", "block",
            "pos", pos.toShortString(),
            "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()
        ));
    }

    /**
     * 同步更新直接旋转和 LookControl，并构造统一成功详情。
     *
     * @param targetPos 世界坐标中的精确注视点
     * @param targetDetails 目标类型相关详情
     * @return COMPLETED 动作应用结果
     */
    private ActionApplyResult applyLook(Vec3 targetPos, Map<String, Object> targetDetails) {
        Mob mob = this.mob();
        if (mob.getEyePosition().distanceToSqr(targetPos) < 1.0E-12) {
            return ActionApplyResult.none(ActionState.failed("look target coincides with agent eyes"));
        }

        // 直接旋转保证动作瞬时生效；LookControl 保证当前原版实体 tick 不会复位俯仰角。
        ActionLook.apply(mob, targetPos);

        var details = new LinkedHashMap<String, Object>(targetDetails);
        details.put("target_x", targetPos.x);
        details.put("target_y", targetPos.y);
        details.put("target_z", targetPos.z);
        details.put("yaw", mob.getYRot());
        details.put("pitch", mob.getXRot());
        return ActionApplyResult.applied(
            ActionControlPolicy.none().disableGoalFlags(Goal.Flag.LOOK),
            ActionState.completed("look target acquired", details)
        );
    }

    /**
     * 动作工厂 —— 为每个环境创建独立的注视动作控制器。
     */
    public static final class Factory implements ActionComponentFactory<ProtoLookAt, LookAtController> {
        /**
         * 创建绑定指定 Agent 的控制器。
         *
         * @param mob 受控 Agent
         * @return 新控制器实例
         */
        @Override
        public LookAtController create(Mob mob) {
            return new LookAtController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return LookAtController 的运行时类型
         */
        @Override
        public Class<LookAtController> componentType() {
            return LookAtController.class;
        }
    }
}
