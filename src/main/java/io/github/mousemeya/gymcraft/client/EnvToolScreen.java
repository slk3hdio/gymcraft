package io.github.mousemeya.gymcraft.client;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import io.github.mousemeya.gymcraft.gym.state.AttachScope;
import io.github.mousemeya.gymcraft.network.EnvToolAttachPayload;
import io.github.mousemeya.gymcraft.network.EnvToolRemovePayload;
import io.github.mousemeya.gymcraft.network.EnvToolSavePayload;
import io.github.mousemeya.gymcraft.network.EnvToolScreenPayload;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * 环境工具管理界面：绑定到单个目标 Mob，支持选择环境类型、设定状态范围半径与
 * 方块/实体快照开关、挂载/重新挂载、保存场景与移除环境。
 * <p>
 * 界面状态由服务端载荷驱动：每次操作后服务端重发 {@link EnvToolScreenPayload}，
 * 界面经 {@link #refresh} 就地更新；按钮动作只发请求，不在本地假设成功。
 * </p>
 */
public final class EnvToolScreen extends Screen {
    private final UUID target;
    private final List<String> envTypes;
    private int selectedIndex;
    /** 目标当前绑定的环境类型；未绑定为 null。 */
    @Nullable private String currentEnvType;
    private boolean captureBlocks;
    private boolean captureEntities;
    /** 半径输入暂存：EditBox 重建（界面刷新）时恢复用户输入。 */
    private int radiusValue;
    /** 场景名输入暂存：EditBox 重建（界面刷新）时恢复用户输入。 */
    private String scenarioName = "";
    private EditBox radiusBox;
    private EditBox nameBox;
    private Button saveButton;
    private Button removeButton;

    /**
     * 按服务端下发的界面状态创建管理界面。
     *
     * @param state 目标与当前环境状态
     */
    public EnvToolScreen(EnvToolScreenPayload state) {
        super(Component.translatable("screen.gymcraft.env_tool.title"));
        this.target = state.target();
        this.envTypes = state.envTypes();
        this.currentEnvType = state.currentEnvType();
        this.selectedIndex = Math.max(0, this.envTypes.indexOf(state.currentEnvType()));
        this.captureBlocks = state.captureBlocks();
        this.captureEntities = state.captureEntities();
        this.radiusValue = state.regionRadius();
    }

    /**
     * 判断界面是否绑定指定目标（刷新载荷路由用）。
     *
     * @param target 目标实体 UUID
     * @return 绑定同一目标时为 true
     */
    public boolean matches(UUID target) {
        return this.target.equals(target);
    }

    /**
     * 用服务端最新状态就地刷新：更新当前环境显示、按钮可用态与未聚焦输入框的内容；
     * 用户正在编辑的输入框保持原文。
     *
     * @param state 最新界面状态
     */
    public void refresh(EnvToolScreenPayload state) {
        this.currentEnvType = state.currentEnvType();
        if (!this.envTypes.equals(state.envTypes())) {
            this.envTypes.clear();
            this.envTypes.addAll(state.envTypes());
            this.selectedIndex = Math.max(0, this.envTypes.indexOf(state.currentEnvType()));
        }
        if (this.radiusBox == null || !this.radiusBox.isFocused()) {
            this.radiusValue = state.regionRadius();
        }
        if (this.nameBox != null && this.nameBox.isFocused()) {
            this.scenarioName = this.nameBox.getValue();
        }
        if (this.currentEnvType != null) {
            this.captureBlocks = state.captureBlocks();
            this.captureEntities = state.captureEntities();
        }
        this.rebuildWidgets();
    }

    /**
     * 构建界面：环境类型循环按钮、范围半径输入、方块/实体快照开关、场景名输入与
     * 保存按钮，底部挂载/移除/完成；目标与当前环境信息只读绘制。
     */
    @Override protected void init() {
        int left = this.width / 2 - 150;
        // 环境类型循环按钮：未选择时回落到列表第一项。
        this.addRenderableWidget(Button.builder(envTypeLabel(), button -> {
            this.selectedIndex = Math.floorMod(this.selectedIndex + 1, this.envTypes.size());
            button.setMessage(envTypeLabel());
        }).bounds(left, 46, 300, 20).build());

        this.radiusBox = new EditBox(this.font, left + 200, 70, 100, 20,
            Component.translatable("screen.gymcraft.env_tool.radius"));
        this.radiusBox.setFilter(text -> text.isEmpty() || text.matches("\\d{1,2}"));
        this.radiusBox.setMaxLength(2);
        this.radiusBox.setResponder(text -> {
            // 过滤已保证非空即 1–2 位数字；清空按 0（仅捕获 agent）处理。
            this.radiusValue = text.isEmpty() ? 0 : Integer.parseInt(text);
        });
        this.radiusBox.setValue(Integer.toString(this.radiusValue));
        this.addRenderableWidget(this.radiusBox);

        this.addRenderableWidget(Button.builder(captureLabel(
                "screen.gymcraft.env_tool.capture_blocks", this.captureBlocks), button -> {
            this.captureBlocks = !this.captureBlocks;
            button.setMessage(captureLabel("screen.gymcraft.env_tool.capture_blocks", this.captureBlocks));
        }).bounds(left, 94, 148, 20).build());
        this.addRenderableWidget(Button.builder(captureLabel(
                "screen.gymcraft.env_tool.capture_entities", this.captureEntities), button -> {
            this.captureEntities = !this.captureEntities;
            button.setMessage(captureLabel("screen.gymcraft.env_tool.capture_entities", this.captureEntities));
        }).bounds(left + 152, 94, 148, 20).build());

        this.nameBox = new EditBox(this.font, left, 118, 194, 20,
            Component.translatable("screen.gymcraft.env_tool.scenario_name"));
        this.nameBox.setSuggestion(Component.translatable("screen.gymcraft.env_tool.scenario_name").getString());
        this.nameBox.setMaxLength(64);
        this.nameBox.setResponder(text -> {
            this.scenarioName = text;
            this.updateActionStates();
        });
        this.addRenderableWidget(this.nameBox);
        this.nameBox.setValue(this.scenarioName);
        this.saveButton = this.addRenderableWidget(Button.builder(
            Component.translatable("screen.gymcraft.env_tool.save"), button -> this.saveScenario()
        ).bounds(left + 198, 118, 102, 20).build());

        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.gymcraft.env_tool.attach"), button -> this.attach()
        ).bounds(left, 146, 98, 20).build());
        this.removeButton = this.addRenderableWidget(Button.builder(
            Component.translatable("screen.gymcraft.env_tool.remove"), button -> this.removeEnv()
        ).bounds(left + 101, 146, 98, 20).build());
        this.addRenderableWidget(Button.builder(
            Component.translatable("screen.gymcraft.env_tool.done"), button -> this.onClose()
        ).bounds(left + 202, 146, 98, 20).build());
        this.updateActionStates();
    }

    /** 发送挂载请求。 */
    private void attach() {
        if (this.envTypes.isEmpty()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new EnvToolAttachPayload(
            this.target, this.envTypes.get(this.selectedIndex), Math.min(this.radiusValue, AttachScope.MAX_RADIUS),
            this.captureBlocks, this.captureEntities));
    }

    /** 发送保存场景请求。 */
    private void saveScenario() {
        String name = this.scenarioName.trim();
        if (name.isEmpty()) {
            return;
        }
        ClientPacketDistributor.sendToServer(new EnvToolSavePayload(this.target, name));
    }

    /** 发送移除环境请求。 */
    private void removeEnv() {
        ClientPacketDistributor.sendToServer(new EnvToolRemovePayload(this.target));
    }

    /** 按当前环境绑定状态与输入内容刷新保存/移除按钮可用态。 */
    private void updateActionStates() {
        if (this.saveButton == null || this.removeButton == null) {
            return;
        }
        boolean bound = this.currentEnvType != null;
        this.saveButton.active = bound && !this.scenarioName.trim().isEmpty();
        this.removeButton.active = bound;
    }

    /** @return 环境类型循环按钮文字 */
    private Component envTypeLabel() {
        String type = this.envTypes.isEmpty() ? "-" : this.envTypes.get(this.selectedIndex);
        return Component.translatable("screen.gymcraft.env_tool.env_type", type);
    }

    /** @return 快照开关按钮文字（名称 + 开/关） */
    private static Component captureLabel(String labelKey, boolean enabled) {
        return Component.translatable(labelKey,
            Component.translatable(enabled ? "screen.gymcraft.env_tool.on" : "screen.gymcraft.env_tool.off"));
    }

    /** @param graphics GUI 提取器 @param mouseX 鼠标横坐标 @param mouseY 鼠标纵坐标 @param partialTick 插值 */
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        int left = this.width / 2 - 150;
        graphics.centeredText(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        String shortUuid = this.target.toString().substring(0, 8);
        graphics.text(this.font, Component.translatable("screen.gymcraft.env_tool.target", shortUuid),
            left, 22, 0xFFAAAAAA);
        Component current = this.currentEnvType == null
            ? Component.translatable("screen.gymcraft.env_tool.none")
            : Component.literal(this.currentEnvType);
        graphics.text(this.font, Component.translatable("screen.gymcraft.env_tool.current_env", current),
            left, 33, 0xFFAAAAAA);
        graphics.text(this.font, Component.translatable("screen.gymcraft.env_tool.radius"), left, 76, 0xFFFFFFFF);
    }

    /** @return false，管理时环境继续执行 */
    @Override public boolean isPauseScreen() {
        return false;
    }
}
