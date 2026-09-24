package io.github.mousemeya.gymcraft.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import io.github.mousemeya.gymcraft.gym.entity.PlayerSimEntity;

/**
 * 玩家模拟实体渲染器 —— 用玩家模型层定义烘焙的标准人形模型渲染
 * {@link PlayerSimEntity}，贴图固定为原版客户端内置的 Steve 皮肤。
 * <p>
 * 基类 {@link HumanoidMobRenderer} 已自带手持物（{@code ItemInHandLayer}）、
 * 自定义头颅与鞘翅层；此处仅追加盔甲层。第二层皮肤外套（jacket/sleeve）
 * 属于 {@code PlayerModel} 私有部件，本实现不渲染（留作后续扩展）。
 * </p>
 */
@OnlyIn(Dist.CLIENT)
public class PlayerSimRenderer
    extends HumanoidMobRenderer<PlayerSimEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
    /** 原版 Steve 皮肤贴图（客户端内置资源）。 */
    private static final Identifier STEVE_TEXTURE =
        Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    /**
     * 创建渲染器：以 {@code ModelLayers.PLAYER} 烘焙标准人形模型并挂盔甲层。
     *
     * @param context 实体渲染器上下文
     */
    public PlayerSimRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(
            this,
            ArmorModelSet.bake(ModelLayers.PLAYER_ARMOR, context.getModelSet(), HumanoidModel::new),
            context.getEquipmentRenderer()));
    }

    /**
     * 返回实体贴图（固定 Steve 皮肤）。
     *
     * @param state 渲染状态
     * @return Steve 皮肤贴图位置
     */
    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return STEVE_TEXTURE;
    }

    /**
     * 创建本渲染器使用的标准人形渲染状态。
     *
     * @return 新渲染状态
     */
    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }
}
