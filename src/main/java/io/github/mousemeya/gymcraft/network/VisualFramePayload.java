package io.github.mousemeya.gymcraft.network;

import io.github.mousemeya.gymcraft.gym.visualization.VisualElement;
import io.github.mousemeya.gymcraft.gym.visualization.VisualFrame;
import java.util.ArrayList;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/** 有界的服务端调试消息；独立于 RL protobuf 协议，双方使用相同版本。 */
public record VisualFramePayload(VisualFrame frame) implements CustomPacketPayload {
    public static final Type<VisualFramePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("gymcraft", "visual_frame"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VisualFramePayload> STREAM_CODEC = StreamCodec.of(
        VisualFramePayload::write, VisualFramePayload::read);

    /**
     * 编码快照；写入完成后校验总长度不超 {@link VisualFrame#MAX_BYTES}，
     * 超限抛出以阻止发送无界包。
     *
     * @param buffer  输出缓冲
     * @param payload 待编码快照
     */
    private static void write(RegistryFriendlyByteBuf buffer, VisualFramePayload payload) {
        int start = buffer.writerIndex();
        VisualFrame frame = payload.frame();
        buffer.writeEnum(frame.kind());
        buffer.writeUUID(frame.subscription());
        buffer.writeUUID(frame.environment());
        buffer.writeUUID(frame.agent());
        buffer.writeUtf(frame.dimension(), 128);
        buffer.writeVarLong(frame.generation());
        buffer.writeVarLong(frame.sequence());
        buffer.writeVarLong(frame.tick());
        buffer.writeVarLong(frame.actionId());
        buffer.writeUtf(frame.component(), 96);
        buffer.writeUtf(frame.status(), 16);
        buffer.writeUtf(frame.description(), 512);
        buffer.writeVarInt(frame.totalElements());
        buffer.writeVarInt(frame.elements().size());
        for (VisualElement element : frame.elements()) writeElement(buffer, element);
        if (buffer.writerIndex() - start > VisualFrame.MAX_BYTES) throw new IllegalArgumentException("Visual frame exceeds budget");
    }

    /**
     * 解码快照；先校验总长度，再校验元素计数合法（非负、不超上限、不大于声明的
     * {@code totalElements}），任何越界输入都以异常拒绝而不是静默截断。
     *
     * @param buffer 输入缓冲
     * @return 校验后的不可变快照
     */
    private static VisualFramePayload read(RegistryFriendlyByteBuf buffer) {
        if (buffer.readableBytes() > VisualFrame.MAX_BYTES) throw new IllegalArgumentException("Visual frame exceeds budget");
        var kind = buffer.readEnum(VisualFrame.Kind.class);
        var subscription = buffer.readUUID();
        var environment = buffer.readUUID();
        var agent = buffer.readUUID();
        String dimension = buffer.readUtf(128);
        long generation = buffer.readVarLong();
        long sequence = buffer.readVarLong();
        long tick = buffer.readVarLong();
        long action = buffer.readVarLong();
        String component = buffer.readUtf(96);
        String status = buffer.readUtf(16);
        String description = buffer.readUtf(512);
        int total = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > VisualFrame.MAX_ELEMENTS || total < count) throw new IllegalArgumentException("Invalid visual count");
        var elements = new ArrayList<VisualElement>(count);
        for (int i = 0; i < count; i++) elements.add(readElement(buffer));
        return new VisualFramePayload(new VisualFrame(kind, subscription, environment, agent, dimension,
            generation, sequence, tick, action, component, status, description, total, elements));
    }

    /** @param buffer 输出缓冲 @param element 图形 */
    private static void writeElement(RegistryFriendlyByteBuf buffer, VisualElement element) {
        buffer.writeEnum(element.shape());
        buffer.writeUtf(element.component(), 96);
        buffer.writeBoolean(element.observation());
        buffer.writeUtf(element.status(), 16);
        writePoint(buffer, element.from());
        writePoint(buffer, element.to());
        buffer.writeUtf(element.label(), 128);
        buffer.writeVarInt(element.entityId());
        buffer.writeBoolean(element.entityUuid() != null);
        if (element.entityUuid() != null) buffer.writeUUID(element.entityUuid());
        writePoint(buffer, element.anchor());
    }

    /** @param buffer 输入缓冲 @return 不可变图形 */
    private static VisualElement readElement(RegistryFriendlyByteBuf buffer) {
        var shape = buffer.readEnum(VisualElement.Shape.class);
        String component = buffer.readUtf(96);
        boolean observation = buffer.readBoolean();
        String status = buffer.readUtf(16);
        Vec3 from = readPoint(buffer);
        Vec3 to = readPoint(buffer);
        String label = buffer.readUtf(128);
        int entityId = buffer.readVarInt();
        var uuid = buffer.readBoolean() ? buffer.readUUID() : null;
        return new VisualElement(shape, component, observation, status, from, to, label, entityId, uuid, readPoint(buffer));
    }

    /** @param buffer 输出缓冲 @param point 有限坐标 */
    private static void writePoint(RegistryFriendlyByteBuf buffer, Vec3 point) {
        buffer.writeDouble(point.x); buffer.writeDouble(point.y); buffer.writeDouble(point.z);
    }

    /** @param buffer 输入缓冲 @return 坐标，有限性由模型验证 */
    private static Vec3 readPoint(RegistryFriendlyByteBuf buffer) {
        return new Vec3(buffer.readDouble(), buffer.readDouble(), buffer.readDouble());
    }

    /** @return 协议类型 */
    @Override public Type<VisualFramePayload> type() { return TYPE; }
}
