package io.github.mousemeya.gymcraft.gym.health;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 伤害日志事件钩子 —— 把 NeoForge 伤害管线的最终结果写入受控 Agent 的伤害日志附件。
 * <p>
 * 监听 {@link LivingDamageEvent.Post}：扣血完成后触发、不可取消，携带减免后的最终
 * 生命伤害与伤害来源。只读记录、不干预伤害流程。仅当受伤实体是挂有
 * {@code gymcraft:damage_log} 附件的 Mob（即受控 Agent）时写入；野生实体的伤害
 * 事件被 {@code hasData} 守卫直接跳过，不会创建附件实例。
 * </p>
 */
public final class DamageLogHooks {
    private DamageLogHooks() {
    }

    /**
     * 受伤事件钩子 —— 提取最终伤害值与来源并追加进该 Mob 的伤害日志。
     *
     * @param event 扣血后触发的伤害事件（damage 为 0 的无效序列也会触发，照常记录）
     */
    public static void onLivingDamagePost(LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof Mob mob) || !mob.hasData(ModAttachments.DAMAGE_LOG)) {
            return;
        }
        var source = event.getSource();
        mob.getData(ModAttachments.DAMAGE_LOG).append(new DamageLog.DamageEntry(
            mob.level().getGameTime(),
            event.getHealthDamage(),
            event.getOriginalDamage(),
            source.getMsgId(),
            entityTypeId(source.getEntity()),
            source.getEntity() == null ? 0 : source.getEntity().getId(),
            directEntityTypeId(source)
        ));
    }

    /**
     * 肇事者实体类型转注册 id（如 {@code minecraft:zombie}）；无实体来源返回空串。
     *
     * @param entity 伤害来源实体，可为 null
     * @return 注册 id 字符串，无来源或未注册时为空串
     */
    private static String entityTypeId(Entity entity) {
        if (entity == null) {
            return "";
        }
        Identifier key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return key == null ? "" : key.toString();
    }

    /**
     * 直接来源实体类型（箭矢、TNT 等）；与肇事者相同或无直接实体时返回空串，
     * 避免观测里出现重复冗余字段。
     *
     * @param source 本次伤害来源
     * @return 直接来源注册 id，无意义时为空串
     */
    private static String directEntityTypeId(net.minecraft.world.damagesource.DamageSource source) {
        Entity direct = source.getDirectEntity();
        return direct != null && direct != source.getEntity() ? entityTypeId(direct) : "";
    }
}
