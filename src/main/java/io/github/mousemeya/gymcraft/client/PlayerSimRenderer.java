package io.github.mousemeya.gymcraft.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.UseAnim;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import io.github.mousemeya.gymcraft.gym.entity.PlayerSimEntity;

/**
 * 玩家模拟实体渲染器 —— 用玩家模型层定义烘焙的标准人形模型渲染
 * {@link PlayerSimEntity}，贴图固定为原版客户端内置的 Steve 皮肤。
 * <p>
 * 基类 {@link HumanoidMobRenderer} 已自带手持物（{@code ItemInHandLayer}）、
 * 自定义头颅与鞘翅层；此处追加玩家内/外层盔甲模型。1.21.1 的手臂姿态由
 * 模型在 {@code setupAnim} 前写入 {@code rightArmPose/leftArmPose}
 * （26.1 起才有渲染器级 {@code getArmPose} 覆盖点，1.21.1 只有
 * {@code PlayerRenderer} 私有逻辑，故按其语义在模型侧重实现）。
 * 第二层皮肤外套（jacket/sleeve）属于 {@code PlayerModel} 私有部件，
 * 本实现不渲染（留作后续扩展）。
 * </p>
 */
@OnlyIn(Dist.CLIENT)
public class PlayerSimRenderer extends HumanoidMobRenderer<PlayerSimEntity, PlayerSimRenderer.PlayerSimPoseModel> {
    /** 原版 Steve 皮肤贴图（客户端内置资源）。 */
    private static final ResourceLocation STEVE_TEXTURE =
        ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    /**
     * 创建渲染器：以 {@code ModelLayers.PLAYER} 烘焙标准人形模型并挂盔甲层。
     *
     * @param context 实体渲染器上下文
     */
    public PlayerSimRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerSimPoseModel(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        this.addLayer(new HumanoidArmorLayer<>(
            this,
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            context.getModelManager()));
    }

    /**
     * 返回实体贴图（固定 Steve 皮肤）。
     *
     * @param entity 玩家模拟实体
     * @return Steve 皮肤贴图位置
     */
    @Override
    public ResourceLocation getTextureLocation(PlayerSimEntity entity) {
        return STEVE_TEXTURE;
    }

    /**
     * 玩家人形模型 —— 在 {@code setupAnim} 前按玩家规则解析双臂姿态，
     * 使普通持物、格挡、弓弩、望远镜、号角与刷子都能播放对应动画。
     */
    public static final class PlayerSimPoseModel extends HumanoidModel<PlayerSimEntity> {

        /**
         * 用玩家层定义的烘焙模型部件创建模型。
         *
         * @param root 烘焙后的模型根部件
         */
        public PlayerSimPoseModel(ModelPart root) {
            super(root);
        }

        /**
         * 先按玩家规则写入双臂姿态与下蹲标记，再执行标准人形动画。
         *
         * @param entity          玩家模拟实体
         * @param limbSwing       步态摆动计数
         * @param limbSwingAmount 步态摆动幅度
         * @param ageInTicks      实体存活刻数
         * @param netHeadYaw      头部偏航角
         * @param headPitch       头部俯仰角
         */
        @Override
        public void setupAnim(PlayerSimEntity entity, float limbSwing, float limbSwingAmount,
            float ageInTicks, float netHeadYaw, float headPitch) {
            // 1.21.1 的 HumanoidModel 自身不回填 crouching，仅 PlayerRenderer 设置；此处补齐
            this.crouching = entity.isCrouching();
            this.applyPlayerArmPoses(entity);
            super.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
        }

        /**
         * 按玩家规则解析主/副手姿态并写入模型字段（语义对齐
         * {@code PlayerRenderer.getArmPose}：主手持双手姿态时按原版规则
         * 降级副手姿态，再按主手左右写入 {@code rightArmPose/leftArmPose}）。
         *
         * @param mob 玩家模拟实体
         */
        private void applyPlayerArmPoses(PlayerSimEntity mob) {
            HumanoidModel.ArmPose mainPose = getArmPose(mob, InteractionHand.MAIN_HAND);
            HumanoidModel.ArmPose offPose = getArmPose(mob, InteractionHand.OFF_HAND);
            if (mainPose.isTwoHanded()) {
                offPose = mob.getOffhandItem().isEmpty()
                    ? HumanoidModel.ArmPose.EMPTY
                    : HumanoidModel.ArmPose.ITEM;
            }
            if (mob.getMainArm() == HumanoidArm.RIGHT) {
                this.rightArmPose = mainPose;
                this.leftArmPose = offPose;
            } else {
                this.rightArmPose = offPose;
                this.leftArmPose = mainPose;
            }
        }

        /**
         * 将单只手的物品状态转换为玩家风格动画姿态。
         *
         * @param mob  玩家模拟实体
         * @param hand 物品所在手
         * @return 与物品及使用状态匹配的手臂姿态
         */
        private static HumanoidModel.ArmPose getArmPose(PlayerSimEntity mob, InteractionHand hand) {
            ItemStack itemInHand = mob.getItemInHand(hand);
            if (itemInHand.isEmpty()) {
                return HumanoidModel.ArmPose.EMPTY;
            }
            // NeoForge 扩展点：物品可自定义持握姿态（优先于原版判定）
            HumanoidModel.ArmPose extensionPose = IClientItemExtensions.of(itemInHand).getArmPose(mob, hand, itemInHand);
            if (extensionPose != null) {
                return extensionPose;
            }
            if (mob.getUsedItemHand() == hand && mob.getUseItemRemainingTicks() > 0) {
                UseAnim useAnim = itemInHand.getUseAnimation();
                if (useAnim == UseAnim.BLOCK) {
                    return HumanoidModel.ArmPose.BLOCK;
                }
                if (useAnim == UseAnim.BOW) {
                    return HumanoidModel.ArmPose.BOW_AND_ARROW;
                }
                if (useAnim == UseAnim.SPEAR) {
                    return HumanoidModel.ArmPose.THROW_SPEAR;
                }
                if (useAnim == UseAnim.CROSSBOW && hand == mob.getUsedItemHand()) {
                    return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
                }
                if (useAnim == UseAnim.SPYGLASS) {
                    return HumanoidModel.ArmPose.SPYGLASS;
                }
                if (useAnim == UseAnim.TOOT_HORN) {
                    return HumanoidModel.ArmPose.TOOT_HORN;
                }
                if (useAnim == UseAnim.BRUSH) {
                    return HumanoidModel.ArmPose.BRUSH;
                }
            } else if (!mob.swinging && itemInHand.is(Items.CROSSBOW) && CrossbowItem.isCharged(itemInHand)) {
                return HumanoidModel.ArmPose.CROSSBOW_HOLD;
            }
            return HumanoidModel.ArmPose.ITEM;
        }
    }
}
