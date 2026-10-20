package io.github.mousemeya.gymcraft.gym;

import java.util.Map;
import java.util.Set;

/**
 * 组件参数感知 —— 接受 reset options 的 {@code components} 键传入的运行参数覆盖。
 * <p>
 * 支持运行参数覆盖的动作/观测组件实现本接口（两个组件基类提供默认实现：不支持任何参数），
 * 组件自主决定暴露哪些参数键（蛇形命名）及其到内部 setter 的映射，与
 * {@link EnvironmentRegionAware} 的组件自治哲学一致。由 {@code AbstractMcEnv} 在 reset
 * 时逐组件调用；参数覆盖<b>跨 reset 持久</b>——组件实例随环境存活，未指定的参数保持当前值。
 * 未知参数键或非法值必须抛 {@link IllegalArgumentException}（reset fail-fast）。
 * </p>
 */
public interface ComponentOptionAware {

    /**
     * 支持的参数键集合（只读，用于校验与错误提示）。
     *
     * @return 参数键集合；不支持参数覆盖的组件返回空集
     */
    Set<String> optionKeys();

    /**
     * 应用一组参数覆盖；键未出现时保持当前值。
     *
     * @param options 参数键值映射（gRPC 数字经 Struct 到达时为 {@link Double}）
     * @throws IllegalArgumentException 未知参数键或值非法
     */
    void applyOptions(Map<String, Object> options);

    /**
     * 拒绝未在 {@code supported} 中声明的参数键（各组件 {@link #applyOptions} 的公共前置校验）。
     *
     * @param componentName 组件名（用于错误提示）
     * @param supported 支持的参数键集合
     * @param options 待校验的参数映射
     * @throws IllegalArgumentException 存在未声明的参数键
     */
    static void rejectUnknownKeys(String componentName, Set<String> supported, Map<String, Object> options) {
        for (String key : options.keySet()) {
            if (!supported.contains(key)) {
                throw new IllegalArgumentException("Component '" + componentName + "' does not support option '" + key
                    + "' (supported: " + supported + ")");
            }
        }
    }
}
