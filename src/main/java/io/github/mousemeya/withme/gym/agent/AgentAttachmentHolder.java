package io.github.mousemeya.withme.gym.agent;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public class AgentAttachmentHolder {
    public static final DeferredRegister<AttachmentType<?>> DEFERRED_REGISTER =
        DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, "withme");

    public final Supplier<AttachmentType<AgentControlState>> AGENT_STATE = DEFERRED_REGISTER.register(
        "agent_control_state", () -> AttachmentType.builder(AgentControlState::new).build()
    );
}
