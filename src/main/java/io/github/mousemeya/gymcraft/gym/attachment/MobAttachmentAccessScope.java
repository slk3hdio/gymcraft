package io.github.mousemeya.gymcraft.gym.attachment;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 环境对 Mob 附件的临时访问作用域。
 * <p>
 * 激活时附加适用且缺失的数据；停用只撤销访问权，不删除持久附件。
 * 作用域自身不序列化，reset 替换实体后必须重新激活。
 * </p>
 */
public final class MobAttachmentAccessScope {
    private final Set<ResourceLocation> allowedIds;

    /** 使用已确认允许的描述器 ID 创建不可变作用域。 */
    private MobAttachmentAccessScope(Set<ResourceLocation> allowedIds) {
        this.allowedIds = Set.copyOf(allowedIds);
    }

    /**
     * 在 Mob 上激活一组附件访问权，并创建适用的缺失附件。
     *
     * @param mob 当前环境 Mob
     * @param specs 环境声明的附件描述器
     * @return 新的作用域实例
     */
    public static MobAttachmentAccessScope activate(Mob mob, Collection<? extends MobAttachmentSpec<?>> specs) {
        Set<ResourceLocation> allowed = new LinkedHashSet<>();
        for (MobAttachmentSpec<?> spec : specs) {
            if (!spec.supports(mob)) {
                continue;
            }
            attachUnchecked(mob, spec);
            allowed.add(spec.id());
        }
        MobAttachmentAccessScope scope = new MobAttachmentAccessScope(allowed);
        mob.setData(ModAttachments.ENV_ATTACHMENT_SCOPE, scope);
        return scope;
    }

    /**
     * 判断 Mob 当前环境是否获准访问指定附件。
     *
     * @param mob 当前 Mob
     * @param spec 附件描述器
     * @return 已授权且附件实际存在时为 true
     */
    public static boolean canAccess(Mob mob, MobAttachmentSpec<?> spec) {
        return mob.hasData(ModAttachments.ENV_ATTACHMENT_SCOPE)
            && mob.getData(ModAttachments.ENV_ATTACHMENT_SCOPE).allowedIds.contains(spec.id())
            && MobAttachmentService.has(mob, spec);
    }

    /**
     * 撤销本作用域；若 Mob 已切换到另一个作用域则不做处理。
     *
     * @param mob 作用域宿主
     */
    public void deactivate(Mob mob) {
        if (mob.hasData(ModAttachments.ENV_ATTACHMENT_SCOPE)
            && mob.getData(ModAttachments.ENV_ATTACHMENT_SCOPE) == this) {
            mob.removeData(ModAttachments.ENV_ATTACHMENT_SCOPE);
        }
    }

    /** 使用通配描述器执行类型安全服务调用，类型只在该私有边界内擦除。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void attachUnchecked(Mob mob, MobAttachmentSpec<?> spec) {
        MobAttachmentService.attach(mob, (MobAttachmentSpec) spec);
    }
}
