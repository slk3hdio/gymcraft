package io.github.mousemeya.gymcraft.gym.observation.component;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoItemStackView;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoSelfState;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 自身状态观测组件 —— 构建 {@link ProtoSelfState}，包含 Mob 实体的基础属性快照。
 * <p>
 * 输出：实体类型、UUID、生命值、位置、速度、姿态、标志位（地面/水中/熔岩/存活）、
 * 当前攻击目标 ID 与主手持物（空手时 {@code main_hand_item} 不设置）。
 * </p>
 */
public class SelfStateObservationCreator extends AbstractObservationComponentCreator<ProtoSelfState> {
    /**
     * 默认观测空间：生命值与坐标/速度/姿态限幅沿用旧手工空间的数值区间，
     * 布尔与文本字段按 descriptor 推导默认约束，主手持物递归无约束嵌套空间
     * （空手不设置该字段，presence 语义下自动跳过校验）。
     */
    private static final MessageSpace<ProtoSelfState> DEFAULT_SPACE = MessageSpace
        .<ProtoSelfState>builder(ProtoSelfState.getDescriptor())
        .range("health", 0, 2048)
        .range("max_health", 0, 2048)
        .range("x", -30_000_000, 30_000_000)
        .range("y", -2048, 2048)
        .range("z", -30_000_000, 30_000_000)
        .range("vx", -1024, 1024)
        .range("vy", -1024, 1024)
        .range("vz", -1024, 1024)
        .range("yaw", -360, 360)
        .range("pitch", -180, 180)
        .range("target_entity_id", 0, Integer.MAX_VALUE)
        .message("main_hand_item", MessageSpace.of(ProtoItemStackView.getDescriptor()))
        .build();

    public SelfStateObservationCreator() {
    }

    @Override
    public Class<ProtoSelfState> protoType() {
        return ProtoSelfState.class;
    }

    @Override
    public McSpace<ProtoSelfState> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public ProtoSelfState create(Mob mob) {
        var builder = ProtoSelfState.newBuilder()
            .setEntityType(BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString())
            .setUuid(mob.getUUID().toString())
            .setHealth(mob.getHealth())
            .setMaxHealth(mob.getMaxHealth())
            .setX(mob.getX()).setY(mob.getY()).setZ(mob.getZ())
            .setVx(mob.getDeltaMovement().x)
            .setVy(mob.getDeltaMovement().y)
            .setVz(mob.getDeltaMovement().z)
            .setYaw(mob.getYRot()).setPitch(mob.getXRot())
            .setOnGround(mob.onGround())
            .setInWater(mob.isInWater())
            .setInLava(mob.isInLava())
            .setAlive(mob.isAlive());
        if (mob.getTarget() != null) {
            builder.setTargetEntityId(mob.getTarget().getId());
        }
        // 主手持物复用菜单观测的物品视图构建，保证两处字段语义一致；空手不设置该字段
        var mainHand = mob.getMainHandItem();
        if (!mainHand.isEmpty()) {
            builder.setMainHandItem(MenuObservationCreator.buildItemView(mainHand, mob.level().registryAccess()));
        }
        return builder.build();
    }

    /**
     * 观测工厂 —— 注册表引用该内部轻量 {@link ObservationComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ObservationComponentFactory<ProtoSelfState, SelfStateObservationCreator> {
        @Override
        public SelfStateObservationCreator create(Mob mob) {
            return new SelfStateObservationCreator();
        }

        /**
         * 返回该工厂创建的具体观测生成器类型。
         *
         * @return SelfStateObservationCreator 的运行时类型
         */
        @Override
        public Class<SelfStateObservationCreator> componentType() {
            return SelfStateObservationCreator.class;
        }
    }
}
