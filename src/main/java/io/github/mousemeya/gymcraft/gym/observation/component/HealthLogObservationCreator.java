package io.github.mousemeya.gymcraft.gym.observation.component;

import java.util.List;

import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.gym.health.DamageLog;
import io.github.mousemeya.gymcraft.gym.observation.AbstractObservationComponentCreator;
import io.github.mousemeya.gymcraft.gym.observation.ObservationComponentFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoDamageRecord;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoHealthLog;
import io.github.mousemeya.gymcraft.gym.space.FieldConstraint;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 生命日志观测组件 —— 返回 agent 自上次观测以来受到的伤害记录（{@code gymcraft:health_log}）。
 * <p>
 * 数据源是挂在 Mob 附件上的 {@link DamageLog}（由 {@code DamageLogHooks} 从
 * {@code LivingDamageEvent.Post} 写入）。组件语义为增量消费：每次 create 取出并
 * 清空上次观测之后的新增记录，无新伤害时返回空列表；reset 替换实体后日志归零，
 * 首个观测必为空。单次返回条数上限可在环境构造期经 {@link #setMaxRecords} 覆盖，
 * 超出上限保留最新记录。将来扩展回血记录时在同一 proto 消息内追加字段。
 * </p>
 */
public class HealthLogObservationCreator extends AbstractObservationComponentCreator<ProtoHealthLog> {
    /** 默认单次观测返回的伤害条数上限。 */
    public static final int DEFAULT_MAX_RECORDS = 16;

    /** 当前环境实例的单次返回条数上限。 */
    private int maxRecords = DEFAULT_MAX_RECORDS;

    /** 创建使用默认返回上限的观测生成器。 */
    public HealthLogObservationCreator() {
    }

    /** 返回该观测对应的 protobuf 类型。 */
    @Override
    public Class<ProtoHealthLog> protoType() {
        return ProtoHealthLog.class;
    }

    /** 返回默认结果空间。 */
    @Override
    public McSpace<ProtoHealthLog> defaultSpace() {
        return buildSpace(DEFAULT_MAX_RECORDS);
    }

    /**
     * 设置单次观测返回的伤害条数上限并同步重建观测空间。
     *
     * @param maxRecords 新的上限（必须为正；超出伤害日志附件容量的部分自然取不到）
     */
    public void setMaxRecords(int maxRecords) {
        if (maxRecords <= 0) {
            throw new IllegalArgumentException("max_records must be positive, got: " + maxRecords);
        }
        this.maxRecords = maxRecords;
        this.setSpace(buildSpace(maxRecords));
    }

    /** 从 Mob 附件取出上次观测以来的新增伤害记录（读后清空）。 */
    @Override
    public ProtoHealthLog create(Mob mob) {
        // 附件由环境构造/reset 时的访问作用域激活；未挂附件的 Mob 无伤害可报
        if (!mob.hasData(ModAttachments.DAMAGE_LOG)) {
            return ProtoHealthLog.getDefaultInstance();
        }
        List<DamageLog.DamageEntry> entries = mob.getData(ModAttachments.DAMAGE_LOG).drainNew();
        var builder = ProtoHealthLog.newBuilder();
        // 观测间隔内的记录超出上限时丢弃最旧，保留最新的 maxRecords 条
        int from = Math.max(0, entries.size() - this.maxRecords);
        for (int i = from; i < entries.size(); i++) {
            DamageLog.DamageEntry entry = entries.get(i);
            builder.addDamageRecords(ProtoDamageRecord.newBuilder()
                .setGameTick(entry.gameTick())
                .setHealthDamage(entry.healthDamage())
                .setOriginalDamage(entry.originalDamage())
                .setDamageType(entry.damageType())
                .setSourceEntityType(entry.sourceEntityType())
                .setSourceEntityId(entry.sourceEntityId())
                .setDirectEntityType(entry.directEntityType()));
        }
        return builder.build();
    }

    /** 构造与指定条数上限匹配的观测空间（条数上限落在 damage_records 的长度约束上）。 */
    private static MessageSpace<ProtoHealthLog> buildSpace(int maxRecords) {
        return MessageSpace.<ProtoHealthLog>builder(ProtoHealthLog.getDescriptor())
            .repeated("damage_records", new FieldConstraint.Nested(MessageSpace.of(ProtoDamageRecord.getDescriptor())), maxRecords)
            .build();
    }

    /** 观测工厂 —— 为每个环境创建独立配置的生成器。 */
    public static final class Factory implements ObservationComponentFactory<ProtoHealthLog, HealthLogObservationCreator> {
        /** 创建观测生成器。 */
        @Override
        public HealthLogObservationCreator create(Mob mob) {
            return new HealthLogObservationCreator();
        }

        /** 返回工厂创建的生成器运行时类型。 */
        @Override
        public Class<HealthLogObservationCreator> componentType() {
            return HealthLogObservationCreator.class;
        }
    }
}
