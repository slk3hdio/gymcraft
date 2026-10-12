package io.github.mousemeya.gymcraft.network;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.mousemeya.gymcraft.GymCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 客户端：打开或刷新环境工具管理界面。
 * 携带目标实体、全部已注册环境类型与目标当前绑定的环境状态（未绑定时字段为空）。
 */
public record EnvToolScreenPayload(
    UUID target,
    List<String> envTypes,
    @Nullable String currentEnvType,
    int regionRadius,
    boolean captureBlocks,
    boolean captureEntities
) implements CustomPacketPayload {
    public static final Type<EnvToolScreenPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(GymCraft.MODID, "env_tool_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EnvToolScreenPayload> STREAM_CODEC = StreamCodec.of(
        EnvToolScreenPayload::write, EnvToolScreenPayload::read);

    /** @param buffer 网络缓冲 @param value 界面状态 */
    private static void write(RegistryFriendlyByteBuf buffer, EnvToolScreenPayload value) {
        buffer.writeUUID(value.target());
        buffer.writeCollection(value.envTypes(), (buf, type) -> buf.writeUtf(type));
        buffer.writeBoolean(value.currentEnvType() != null);
        if (value.currentEnvType() != null) {
            buffer.writeUtf(value.currentEnvType());
        }
        buffer.writeVarInt(value.regionRadius());
        buffer.writeBoolean(value.captureBlocks());
        buffer.writeBoolean(value.captureEntities());
    }

    /** @param buffer 网络缓冲 @return 界面状态 */
    private static EnvToolScreenPayload read(RegistryFriendlyByteBuf buffer) {
        UUID target = buffer.readUUID();
        List<String> envTypes = buffer.readList(buf -> buf.readUtf());
        String currentEnvType = buffer.readBoolean() ? buffer.readUtf() : null;
        return new EnvToolScreenPayload(target, envTypes, currentEnvType,
            buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean());
    }

    /** @return 协议类型 */
    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
