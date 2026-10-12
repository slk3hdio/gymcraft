package io.github.mousemeya.gymcraft.network;

import java.util.UUID;

import io.github.mousemeya.gymcraft.GymCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：按界面设定向目标 Mob 挂载（或重新挂载）环境。
 * 范围以目标方块位置为锚、六个方向各自独立延伸（西/东/下/上/北/南）。
 */
public record EnvToolAttachPayload(
    UUID target,
    String envType,
    int west,
    int east,
    int down,
    int up,
    int north,
    int south,
    boolean captureBlocks,
    boolean captureEntities
) implements CustomPacketPayload {
    public static final Type<EnvToolAttachPayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(GymCraft.MODID, "env_tool_attach"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EnvToolAttachPayload> STREAM_CODEC = StreamCodec.of(
        EnvToolAttachPayload::write, EnvToolAttachPayload::read);

    /** @param buffer 网络缓冲 @param value 挂载请求 */
    private static void write(RegistryFriendlyByteBuf buffer, EnvToolAttachPayload value) {
        buffer.writeUUID(value.target());
        buffer.writeUtf(value.envType());
        buffer.writeVarInt(value.west());
        buffer.writeVarInt(value.east());
        buffer.writeVarInt(value.down());
        buffer.writeVarInt(value.up());
        buffer.writeVarInt(value.north());
        buffer.writeVarInt(value.south());
        buffer.writeBoolean(value.captureBlocks());
        buffer.writeBoolean(value.captureEntities());
    }

    /** @param buffer 网络缓冲 @return 挂载请求 */
    private static EnvToolAttachPayload read(RegistryFriendlyByteBuf buffer) {
        return new EnvToolAttachPayload(buffer.readUUID(), buffer.readUtf(),
            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readBoolean(), buffer.readBoolean());
    }

    /** @return 协议类型 */
    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
