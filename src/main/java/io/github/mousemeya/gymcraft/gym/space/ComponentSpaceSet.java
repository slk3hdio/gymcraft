package io.github.mousemeya.gymcraft.gym.space;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mousemeya.gymcraft.gym.space.proto.ProtoComponentSpace;
import io.github.mousemeya.gymcraft.gym.space.proto.ProtoComponentSpaceSet;

/**
 * 组件空间目录 —— 环境级动作/观测空间的有序集合。
 * <p>
 * 环境的动作/观测空间不是单一消息类型，而是「组件注册 ID → 该组件消息空间」的目录；
 * 本类承载该目录并按插入顺序输出结构化描述（{@link #toSpec()}），
 * 供 gRPC Connect 响应下发。键序即组件声明顺序，客户端依赖该顺序向 LLM 罗列动作。
 * </p>
 *
 * @param components 组件注册 ID 到其消息空间的有序映射
 */
public record ComponentSpaceSet(Map<String, ? extends McSpace<?>> components) {

    public ComponentSpaceSet {
        components = Collections.unmodifiableMap(new LinkedHashMap<>(components));
    }

    /**
     * 输出结构化空间目录描述（组件顺序与声明顺序一致）。
     *
     * @return 组件空间目录的 proto 描述
     */
    public ProtoComponentSpaceSet toSpec() {
        ProtoComponentSpaceSet.Builder spec = ProtoComponentSpaceSet.newBuilder();
        this.components.forEach((componentId, space) -> spec.addComponents(ProtoComponentSpace.newBuilder()
            .setComponentId(componentId)
            .setSpace(space.spec())));
        return spec.build();
    }
}
