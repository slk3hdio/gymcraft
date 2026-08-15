package io.github.mousemeya.gymcraft.gym.menu;

/**
 * 菜单按钮视图 —— 适配器声明的按钮元数据（对应 {@code ProtoMenuButton}）。
 * <p>
 * 元数据必须与执行结果一致：{@code enabled=false} 的按钮执行时必须在适配器层失败，
 * 未声明的 {@code buttonId} 不得进入原版 {@code clickMenuButton}。
 * </p>
 */
public record MenuButtonView(int buttonId, String name, boolean enabled) {
}
