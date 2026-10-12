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
 * 范围以目标方块位置为锚、六个方向各自独立延伸（西/东/下/上/北/南），并携带
 * 各方向延伸距离上限（水平为钳制上限，垂直为到世界边界的实际距离，随目标位置变化）。
 */
public record EnvToolScreenPayload(
    UUID target,
    List<String> envTypes,
    @Nullable String currentEnvType,
    int west,
    int east,
    int down,
    int up,
    int north,
    int south,
    int maxWest,
    int maxEast,
    int maxDown,
    int maxUp,
    int maxNorth,
    int maxSouth,
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
        buffer.writeVarInt(value.west());
        buffer.writeVarInt(value.east());
        buffer.writeVarInt(value.down());
        buffer.writeVarInt(value.up());
        buffer.writeVarInt(value.north());
        buffer.writeVarInt(value.south());
        buffer.writeVarInt(value.maxWest());
        buffer.writeVarInt(value.maxEast());
        buffer.writeVarInt(value.maxDown());
        buffer.writeVarInt(value.maxUp());
        buffer.writeVarInt(value.maxNorth());
        buffer.writeVarInt(value.maxSouth());
        buffer.writeBoolean(value.captureBlocks());
        buffer.writeBoolean(value.captureEntities());
    }

    /** @param buffer 网络缓冲 @return 界面状态 */
    private static EnvToolScreenPayload read(RegistryFriendlyByteBuf buffer) {
        UUID target = buffer.readUUID();
        List<String> envTypes = buffer.readList(buf -> buf.readUtf());
        String currentEnvType = buffer.readBoolean() ? buffer.readUtf() : null;
        return new EnvToolScreenPayload(target, envTypes, currentEnvType,
            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
            buffer.readBoolean(), buffer.readBoolean());
    }

    /** @return 协议类型 */
    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
