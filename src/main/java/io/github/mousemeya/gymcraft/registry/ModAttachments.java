package io.github.mousemeya.gymcraft.registry;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.component.UseItemConsumption;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 附件类型注册入口 —— 通过 {@link DeferredRegister} 将 {@link AttachmentType}
 * 挂载到 NeoForge 内置的 {@link NeoForgeRegistries#ATTACHMENT_TYPES} 注册表上。
 * <p>
 * 组件的会话状态按需注册为附件并挂在 Mob 实体上
 * （{@code mob.getData(...)} / {@code mob.setData(...)}），每次操作经当前 Mob 读取，
 * 天然跟随 reset 后的新实体实例。
 * </p>
 */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTRY = DeferredRegister.create(
        NeoForgeRegistries.ATTACHMENT_TYPES,
        GymCraft.MODID
    );

    /**
     * 逻辑菜单会话附件 —— 打开菜单时 {@code setData}，关闭时 {@code removeData}；
     * 附件存在当且仅当会话打开。纯运行时状态，不提供序列化 codec。
     * 读取必须经 {@code hasData} 守卫（见 {@code LogicalMenuSessions.current}），
     * 默认值供应商不被使用。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<LogicalMenuSession>> MENU_SESSION = REGISTRY.register(
        "menu_session",
        () -> AttachmentType.<LogicalMenuSession>builder(() -> {
            throw new IllegalStateException("LogicalMenuSession attachment has no default; always guard reads with hasData");
        }).build()
    );

    /** 物品消费会话的纯运行时附件；读取前必须检查 hasData。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<UseItemConsumption>> ITEM_CONSUMPTION = REGISTRY.register(
        "item_consumption",
        () -> AttachmentType.<UseItemConsumption>builder(() -> {
            throw new IllegalStateException("Item consumption must be explicitly created");
        }).build()
    );

    /** 禁止实例化注册入口。 */
    private ModAttachments() {
    }
}
