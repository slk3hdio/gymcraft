package io.github.mousemeya.gymcraft.gym.action.component;

import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAt;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;

/**
 * 注视目标动作组件 —— 将 Agent 的当前视线瞬时对准实体、掉落物或方块。
 * <p>
 * 普通实体目标接受已加载、存活且不是掉落物的 {@link Entity}，注视其眼睛位置；
 * 掉落物目标只接受有效的 {@link ItemEntity}，注视其包围盒中心；方块目标只需位于
 * 已加载区块，空气也可作为目标，注视对应方块坐标中心。动作会同步实体朝向、俯仰角和头部朝向，
 * 同时向 {@code LookControl} 写入同一目标，避免当前实体 tick 把视线立即复位。
 * </p>
 * <p>
 * 该动作为瞬时动作，应用后立即返回终态；不限制注视距离，也不要求目标可见。
 * </p>
 */
public class LookAtController extends AbstractActionComponentController<ProtoLookAt> implements EnvironmentRegionAware {

    /** 动作空间沿用现有 oneof 组件的扁平字段表达。 */
    private static final DictSpace DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

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

    /**
     * 返回默认动作空间。
     *
     * @return 扁平化的坐标与实体 ID 空间
     */
    @Override
    public DictSpace defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 校验 oneof 目标是否已设置且字段位于动作空间内。
     *
     * @param component 注视动作负载
     * @return 负载结构与数值是否有效
     */
    @Override
    public boolean contains(ProtoLookAt component) {
        if (component == null) {
            return false;
        }
        return switch (component.getTargetCase()) {
            case ENTITY -> component.getEntity().getEntityId() > 0
                && this.containsEntityId(component.getEntity().getEntityId());
            case ITEM -> component.getItem().getEntityId() > 0
                && this.containsEntityId(component.getItem().getEntityId());
            case BLOCK -> this.space().containsFields(Map.of(
                "x", new double[] { component.getBlock().getX() },
                "y", new double[] { component.getBlock().getY() },
                "z", new double[] { component.getBlock().getZ() }
            ));
            case TARGET_NOT_SET -> false;
        };
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
        if (component != null && component.hasBlock()
            && !this.containsTarget(component.getBlock().getX(), component.getBlock().getY(), component.getBlock().getZ())) {
            return ActionApplyResult.none(this.outsideTarget(component.getBlock().getX(), component.getBlock().getY(), component.getBlock().getZ()));
        }
        if (!this.contains(component)) {
            return ActionApplyResult.none(ActionState.failed("invalid action payload"));
        }
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
     * 按扁平动作空间校验实体 ID。
     *
     * @param entityId 网络实体 ID
     * @return ID 是否位于空间边界内
     */
    private boolean containsEntityId(int entityId) {
        return this.space().containsFields(Map.of(
            "entity_id", new double[] { entityId }
        ));
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
        ActionState boundaryError = this.checkTargetEntity(found);
        if (boundaryError != null) return ActionApplyResult.none(boundaryError);
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
        ActionState boundaryError = this.checkTargetEntity(item);
        if (boundaryError != null) return ActionApplyResult.none(boundaryError);
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


    /** @param region 构造期默认区域；环境先注入区域，再应用具体环境的空间配置 */
    @Override
    public void initializeDefaultRegion(@Nullable BoundingBox region) {
        if (region != null) {
            this.setSpace(CoordinateSpaces.withRegion(this.space(), region, true));
        }
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 是否落在本组件当前坐标空间
     */
    private boolean containsTarget(double x, double y, double z) {
        return this.space().containsFields(Map.of(
            "x", new double[] {x}, "y", new double[] {y}, "z", new double[] {z}));
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 本组件的越界失败及空间诊断
     */
    private ActionState outsideTarget(double x, double y, double z) {
        return ActionState.failed("target outside action space", CoordinateSpaces.details(this.space(), x, y, z));
    }

    /**
     * @param target 已解析实体
     * @return 维度或位置不符合本组件空间时的失败；合法返回 null
     */
    private ActionState checkTargetEntity(net.minecraft.world.entity.Entity target) {
        if (target.level() != this.mob().level()) {
            return ActionState.failed("target entity is in another dimension", Map.of("entity_id", target.getId()));
        }
        return this.containsTarget(target.getX(), target.getY(), target.getZ())
            ? null : this.outsideTarget(target.getX(), target.getY(), target.getZ());
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
