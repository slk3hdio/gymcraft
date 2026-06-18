package io.github.mousemeya.withme.gym.agent;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * NeoForge 数据附件（Data Attachment）注册持有类。
 * <p>
 * 通过 {@link DeferredRegister} 注册自定义的 {@link AttachmentType}，
 * 使得可以在任何 Mob 实体上附加 {@link AgentControlState} 数据，
 * 用于存储智能体的控制状态（动作队列、观测、目标位置等）。
 * <p>
 * 注意：所有字段必须为 static，确保在模组构造阶段（RegisterEvent 之前）完成注册。
 */
public class AgentAttachmentHolder {
    // 附件类型的延迟注册器，注册到 NeoForge 的 ATTACHMENT_TYPES 注册表
    public static final DeferredRegister<AttachmentType<?>> DEFERRED_REGISTER =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "withme");

    // 注册 AgentControlState 附件类型，每个 Mob 可以通过此附件存储智能体控制状态
    public static final Supplier<AttachmentType<AgentControlState>> AGENT_STATE = DEFERRED_REGISTER.register(
        "agent_control_state", () -> AttachmentType.builder(AgentControlState::new).build()
    );
}
