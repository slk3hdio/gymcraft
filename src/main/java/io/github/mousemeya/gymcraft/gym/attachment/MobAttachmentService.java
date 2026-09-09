package io.github.mousemeya.gymcraft.gym.attachment;

import java.util.Optional;

import net.minecraft.world.entity.Mob;

/**
 * 通用 Mob 附件服务：提供与环境、Agent 和 RPC 无关的附加与访问入口。
 * <p>
 * 持久化、同步和复制策略由描述器引用的 NeoForge {@code AttachmentType} 决定。
 * </p>
 */
public final class MobAttachmentService {
    /** 禁止实例化静态服务。 */
    private MobAttachmentService() {
    }

    /**
     * 为 Mob 附加或取得指定数据。
     *
     * @param mob 宿主 Mob
     * @param spec 附件描述器
     * @param <T> 附件数据类型
     * @return 已存在或新创建的数据
     * @throws IllegalArgumentException Mob 不满足描述器宿主约束时抛出
     */
    public static <T> T attach(Mob mob, MobAttachmentSpec<T> spec) {
        if (!spec.supports(mob)) {
            throw new IllegalArgumentException("Mob does not support attachment " + spec.id() + ": " + mob.getUUID());
        }
        return mob.getData(spec.attachmentType());
    }

    /**
     * 在不创建默认值的前提下读取附件。
     *
     * @param mob 宿主 Mob
     * @param spec 附件描述器
     * @param <T> 附件数据类型
     * @return 已存在的数据，否则为空
     */
    public static <T> Optional<T> getIfPresent(Mob mob, MobAttachmentSpec<T> spec) {
        return mob.hasData(spec.attachmentType())
            ? Optional.of(mob.getData(spec.attachmentType()))
            : Optional.empty();
    }

    /**
     * 判断附件是否已经存在，不触发默认创建。
     *
     * @param mob 宿主 Mob
     * @param spec 附件描述器
     * @return 是否存在
     */
    public static boolean has(Mob mob, MobAttachmentSpec<?> spec) {
        return mob.hasData(spec.attachmentType());
    }

    /**
     * 显式移除附件。
     *
     * @param mob 宿主 Mob
     * @param spec 附件描述器
     * @param <T> 附件数据类型
     * @return 被移除的数据，否则为空
     */
    public static <T> Optional<T> remove(Mob mob, MobAttachmentSpec<T> spec) {
        return Optional.ofNullable(mob.removeData(spec.attachmentType()));
    }
}
