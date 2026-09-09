package io.github.mousemeya.gymcraft.gym.attachment;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.attachment.AttachmentType;

/**
 * Mob 附件描述器：以稳定 ID、NeoForge 附件类型和宿主约束描述一种可复用能力。
 * <p>
 * 描述器本身不依赖环境或 Agent；环境只持有描述器集合来声明访问权。
 * </p>
 *
 * @param <T> 附件数据类型
 */
public final class MobAttachmentSpec<T> {
    private final Identifier id;
    private final Supplier<? extends AttachmentType<T>> attachmentType;
    private final Predicate<Mob> supports;

    /**
     * 创建类型安全的 Mob 附件描述器。
     *
     * @param id 稳定描述器 ID
     * @param attachmentType 已注册的 NeoForge 附件类型
     * @param supports 判断指定 Mob 是否支持该附件
     */
    public MobAttachmentSpec(
        Identifier id,
        Supplier<? extends AttachmentType<T>> attachmentType,
        Predicate<Mob> supports
    ) {
        this.id = Objects.requireNonNull(id);
        this.attachmentType = Objects.requireNonNull(attachmentType);
        this.supports = Objects.requireNonNull(supports);
    }

    /** @return 稳定描述器 ID */
    public Identifier id() {
        return this.id;
    }

    /** @return 已注册的 NeoForge 附件类型 */
    public AttachmentType<T> attachmentType() {
        return this.attachmentType.get();
    }

    /**
     * 判断 Mob 是否允许附加该数据。
     *
     * @param mob 候选宿主
     * @return 是否支持
     */
    public boolean supports(Mob mob) {
        return this.supports.test(mob);
    }
}
