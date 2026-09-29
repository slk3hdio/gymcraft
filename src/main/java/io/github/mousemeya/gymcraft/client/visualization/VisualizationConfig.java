package io.github.mousemeya.gymcraft.client.visualization;

import java.util.ArrayList;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 仅客户端加载的显示偏好；图层键含来源，避免同名动作和观测相互影响。 */
public final class VisualizationConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    /** 总开关；关闭时立即取消订阅并停止渲染。 */
    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER.define("visualization.enabled", true);
    /** 是否允许图形穿墙渲染（原生 Gizmo 的 always-on-top）。 */
    public static final ModConfigSpec.BooleanValue THROUGH_WALLS = BUILDER.define("visualization.throughWalls", true);
    /** 是否显示图形文字标签。 */
    public static final ModConfigSpec.BooleanValue LABELS = BUILDER.define("visualization.labels", true);
    /** 观测方框仅绘制八个角的短线框，降低密集线框对画面的淹没；动作图形不受影响。 */
    public static final ModConfigSpec.BooleanValue COMPACT_BOXES = BUILDER.define("visualization.compactBoxes", true);
    /** 观测图形按观察者距离线性淡出的截止距离（格），半程处开始淡出；0 表示不淡出。 */
    public static final ModConfigSpec.DoubleValue FADE_DISTANCE = BUILDER
        .defineInRange("visualization.fadeDistance", 32.0, 0.0, 128.0);
    /** 被隐藏的图层键列表；键格式为 {@code action/<组件ID>} 或 {@code observation/<组件ID>}。 */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> HIDDEN = BUILDER.defineListAllowEmpty(
        "visualization.hiddenLayers", List.of(), () -> "action/gymcraft:noop", value -> value instanceof String);
    /** 配置规格；注册为客户端配置后支持模组列表配置界面编辑与保存。 */
    public static final ModConfigSpec SPEC = BUILDER.build();
    /** 设置界面的图层清单；顺序即分页顺序。 */
    public static final List<String> LAYERS = List.of(
        "action/gymcraft:step_move", "action/gymcraft:look_at", "action/gymcraft:move_to",
        "action/gymcraft:set_attack_target", "action/gymcraft:attack_once", "action/gymcraft:noop",
        "action/gymcraft:jump", "action/gymcraft:break_block", "action/gymcraft:set_block",
        "action/gymcraft:open_menu", "action/gymcraft:close_menu", "action/gymcraft:move_menu_item",
        "action/gymcraft:click_menu_button", "action/gymcraft:pick_up_item", "action/gymcraft:drop_item",
        "action/gymcraft:use_item", "action/gymcraft:update_interesting_blocks", "action/gymcraft:send_chat",
        "observation/gymcraft:self", "observation/gymcraft:world", "observation/gymcraft:nearby_entities",
        "observation/gymcraft:nearby_items", "observation/gymcraft:nearby_blocks",
        "observation/gymcraft:interesting_blocks", "observation/gymcraft:menu", "observation/gymcraft:chat");

    /** 禁止实例化配置目录。 */
    private VisualizationConfig() { }

    /** @param key 来源与组件组成的图层键 @return 图层是否显示 */
    public static boolean visible(String key) { return !HIDDEN.get().contains(key); }

    /**
     * 图层键到语言文件键的映射。
     *
     * <p>{@code "action/gymcraft:step_move"} → {@code "layer.gymcraft.action.step_move"}；
     * 客户端配合 {@code translatableWithFallback} 使用，未翻译的组件回退显示短名。</p>
     *
     * @param layer 图层键
     * @return 语言文件键
     */
    public static String translationKey(String layer) {
        int slash = layer.indexOf('/');
        int colon = layer.indexOf(':');
        return "layer." + layer.substring(slash + 1, colon) + "." + layer.substring(0, slash) + "." + layer.substring(colon + 1);
    }

    /**
     * 切换图层可见性并立即持久化到客户端配置文件。
     *
     * @param key 图层键
     */
    public static void toggle(String key) {
        var hidden = new ArrayList<String>(HIDDEN.get());
        if (!hidden.remove(key)) hidden.add(key);
        HIDDEN.set(hidden);
        SPEC.save();
    }
}
