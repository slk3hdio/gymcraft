package io.github.mousemeya.gymcraft.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

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
     * 按玩家规则解析手臂姿态，使普通持物、格挡、弓弩、望远镜、号角、
     * 刷子与长柄武器都能播放对应动画。
     *
     * @param mob 玩家模拟实体
     * @param arm 待解析的手臂
     * @return 当前手臂应采用的人形模型姿态
     */
    @Override
    protected HumanoidModel.ArmPose getArmPose(PlayerSimEntity mob, HumanoidArm arm) {
        InteractionHand hand = mob.getMainArm() == arm ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        ItemStack itemInHand = mob.getItemInHand(hand);
        HumanoidModel.ArmPose pose = getItemArmPose(mob, itemInHand, hand);
        if (hand == InteractionHand.OFF_HAND && getItemArmPose(
            mob,
            mob.getMainHandItem(),
            InteractionHand.MAIN_HAND
        ).isTwoHanded()) {
            return itemInHand.isEmpty() ? HumanoidModel.ArmPose.EMPTY : HumanoidModel.ArmPose.ITEM;
        }
        return pose;
    }

    /**
     * 将单只手的物品状态转换为玩家风格动画姿态。
     *
     * @param mob 玩家模拟实体
     * @param itemInHand 手中的物品
     * @param hand 物品所在手
     * @return 与物品及使用状态匹配的手臂姿态
     */
    private static HumanoidModel.ArmPose getItemArmPose(
        PlayerSimEntity mob,
        ItemStack itemInHand,
        InteractionHand hand
    ) {
        HumanoidModel.ArmPose extensionPose = IClientItemExtensions.of(itemInHand).getArmPose(mob, hand, itemInHand);
        if (extensionPose != null) {
            return extensionPose;
        }
        if (itemInHand.isEmpty()) {
            return HumanoidModel.ArmPose.EMPTY;
        }
        if (!mob.swinging && itemInHand.is(Items.CROSSBOW) && CrossbowItem.isCharged(itemInHand)) {
            return HumanoidModel.ArmPose.CROSSBOW_HOLD;
        }
        if (mob.getUsedItemHand() == hand && mob.getUseItemRemainingTicks() > 0) {
            HumanoidModel.ArmPose usePose = switch (itemInHand.getUseAnimation()) {
                case BLOCK -> HumanoidModel.ArmPose.BLOCK;
                case BOW -> HumanoidModel.ArmPose.BOW_AND_ARROW;
                case TRIDENT -> HumanoidModel.ArmPose.THROW_TRIDENT;
                case CROSSBOW -> HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                case SPYGLASS -> HumanoidModel.ArmPose.SPYGLASS;
                case TOOT_HORN -> HumanoidModel.ArmPose.TOOT_HORN;
                case BRUSH -> HumanoidModel.ArmPose.BRUSH;
                case SPEAR -> HumanoidModel.ArmPose.SPEAR;
                default -> null;
            };
            if (usePose != null) {
                return usePose;
            }
        }
        SwingAnimation swingAnimation = itemInHand.get(DataComponents.SWING_ANIMATION);
        if (swingAnimation != null && swingAnimation.type() == SwingAnimationType.STAB && mob.swinging) {
            return HumanoidModel.ArmPose.SPEAR;
        }
        return itemInHand.is(ItemTags.SPEARS)
            ? HumanoidModel.ArmPose.SPEAR
            : HumanoidModel.ArmPose.ITEM;
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
