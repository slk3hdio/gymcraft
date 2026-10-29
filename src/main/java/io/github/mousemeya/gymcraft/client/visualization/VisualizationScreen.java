package io.github.mousemeya.gymcraft.client.visualization;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

/** 非暂停的分页图层设置界面；配置修改即时生效并保存到客户端配置。 */
public final class VisualizationScreen extends Screen {
    /** 淡出距离的可选档位（格）；0 表示关闭距离淡出。 */
    private static final double[] FADE_CHOICES = {0, 16, 32, 64, 128};
    private int page;

    /** 创建可视化设置界面；标题取自语言文件。 */
    public VisualizationScreen() { super(Component.translatable("screen.gymcraft.visual.title")); }

    /**
     * 构建界面：顶部五个总开关（显示/穿墙/标签/角框/淡出），中部按窗口高度分页的
     * 图层开关，底部翻页与完成按钮。
     */
    @Override protected void init() {
        int left = width / 2 - 165;
        toggleButton(left, "screen.gymcraft.visual.option.show", VisualizationConfig.ENABLED, 62);
        toggleButton(left + 67, "screen.gymcraft.visual.option.through_walls", VisualizationConfig.THROUGH_WALLS, 62);
        toggleButton(left + 134, "screen.gymcraft.visual.option.labels", VisualizationConfig.LABELS, 62);
        toggleButton(left + 201, "screen.gymcraft.visual.option.compact_boxes", VisualizationConfig.COMPACT_BOXES, 62);
        fadeButton(left + 268);
        // 每页行数随窗口高度自适应，双列布局；避免高 GUI 缩放下按钮超出屏幕。
        int rows = Math.max(1, Math.min(6, (height - 140) / 24));
        int pageSize = rows * 2;
        int pages = (VisualizationConfig.LAYERS.size() + pageSize - 1) / pageSize;
        page = Math.min(page, pages - 1);
        for (int offset = 0; offset < pageSize; offset++) {
            int index = page * pageSize + offset;
            if (index >= VisualizationConfig.LAYERS.size()) break;
            String key = VisualizationConfig.LAYERS.get(index);
            addRenderableWidget(Button.builder(layerLabel(key), button -> {
                VisualizationConfig.toggle(key);
                button.setMessage(layerLabel(key));
            }).bounds(left + (offset % 2) * 158, 64 + (offset / 2) * 24, 152, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("screen.gymcraft.visual.prev_page"), button -> {
            page = Math.floorMod(page - 1, pages); rebuildWidgets();
        }).bounds(left, height - 52, 95, 20).build());
        addRenderableWidget(Button.builder(Component.literal((page + 1) + " / " + pages), button -> { })
            .bounds(left + 108, height - 52, 95, 20).build()).active = false;
        addRenderableWidget(Button.builder(Component.translatable("screen.gymcraft.visual.next_page"), button -> {
            page = (page + 1) % pages; rebuildWidgets();
        }).bounds(left + 216, height - 52, 95, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.gymcraft.visual.done"), button -> onClose())
            .bounds(width / 2 - 60, height - 27, 120, 20).build());
    }

    /**
     * 添加一个布尔配置开关按钮；点击即取反并保存。
     *
     * <p>按钮文字由语言文件的 {@code "%s: %s"} 模板组合名称与开/关状态。</p>
     *
     * @param x        横坐标
     * @param labelKey 名称的翻译键
     * @param option   布尔配置项
     * @param width    按钮宽度
     */
    private void toggleButton(int x, String labelKey, ModConfigSpec.BooleanValue option, int width) {
        addRenderableWidget(Button.builder(Component.translatable("screen.gymcraft.visual.toggle",
            Component.translatable(labelKey),
            Component.translatable(option.get() ? "screen.gymcraft.visual.on" : "screen.gymcraft.visual.off")), button -> {
            option.set(!option.get());
            VisualizationConfig.SPEC.save();
            if (option == VisualizationConfig.ENABLED && !option.get()) VisualizationClient.cancel();
            rebuildWidgets();
        }).bounds(x, 34, width, 20).build());
    }

    /**
     * 添加观测淡出距离的循环档位按钮：关 → 16 → 32 → 64 → 128 → 关。
     *
     * @param x 横坐标
     */
    private void fadeButton(int x) {
        addRenderableWidget(Button.builder(fadeLabel(), button -> {
            double current = VisualizationConfig.FADE_DISTANCE.get();
            int index = FADE_CHOICES.length - 1;
            for (int i = 0; i < FADE_CHOICES.length; i++) {
                if (FADE_CHOICES[i] == current) { index = i; break; }
            }
            VisualizationConfig.FADE_DISTANCE.set(FADE_CHOICES[(index + 1) % FADE_CHOICES.length]);
            VisualizationConfig.SPEC.save();
            button.setMessage(fadeLabel());
        }).bounds(x, 34, 62, 20).build());
    }

    /** @return 当前淡出档位的显示文字；关闭档位复用通用「关」翻译 */
    private static Component fadeLabel() {
        double value = VisualizationConfig.FADE_DISTANCE.get();
        return Component.translatable("screen.gymcraft.visual.fade",
            value <= 0 ? Component.translatable("screen.gymcraft.visual.off")
                : Component.literal(String.valueOf((int) value)));
    }

    /**
     * 图层开关文字：✓/× 表示可见性，A/O 前缀区分动作与观测，名称经语言文件翻译、
     * 缺失时回退组件短名。
     *
     * @param key 图层键
     * @return 开关文字
     */
    private static Component layerLabel(String key) {
        String prefix = key.startsWith("observation/") ? "O " : "A ";
        String path = key.substring(key.indexOf(':') + 1);
        String name = Component.translatableWithFallback(VisualizationConfig.translationKey(key), path).getString();
        return Component.literal((VisualizationConfig.visible(key) ? "✓ " : "× ") + prefix + name);
    }

    /** @param graphics GUI 提取器 @param mouseX 鼠标横坐标 @param mouseY 鼠标纵坐标 @param partialTick 插值 */
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFFFF);
    }

    /** @return false，设置时环境继续执行 */
    @Override public boolean isPauseScreen() { return false; }
}
