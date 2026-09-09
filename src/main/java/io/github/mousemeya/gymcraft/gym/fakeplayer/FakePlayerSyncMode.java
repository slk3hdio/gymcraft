package io.github.mousemeya.gymcraft.gym.fakeplayer;

/**
 * FakePlayer 与 Mob 的状态同步模式。
 * <p>
 * 不同原版入口对执行者坐标和状态的读取不同：菜单与挖掘按脚部位置工作，
 * 物品交互则需要让 FakePlayer 的眼睛与 Mob 眼睛重合。
 * </p>
 */
public enum FakePlayerSyncMode {
    /** 菜单会话：仅同步脚部位置与朝向，保留活动菜单拥有的其他状态。 */
    MENU_FEET,
    /** 手部动作：同步脚部位置、朝向、落地状态、效果、游戏模式和主手。 */
    HAND_ACTION,
    /** 物品使用：按眼高对齐位置，并同步朝向、落地状态和游戏模式。 */
    ITEM_USE_EYES
}
