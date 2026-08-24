package net.okitsu.ysmepicfightcompat.animation;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.UseAnim;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.animation.types.DynamicAnimation;
import yesman.epicfight.api.animation.types.ReboundAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.client.animation.ClientAnimator;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Item/action condition matching shared by automatic clips and Molang ctrl calls. */
final class AnimationConditionMatcher {
    enum ItemAction {
        HOLD,
        USE,
        SWING
    }

    /** One continuous vanilla or Epic Fight attack playback. */
    record SwingSignal(boolean active, String source, float elapsed) {
        private static final SwingSignal INACTIVE = new SwingSignal(false, "", 0.0F);
    }

    private AnimationConditionMatcher() {
    }

    static boolean hold(LivingEntity entity, String handName, String selector) {
        InteractionHand hand = hand(handName);
        if (hand == null || isUsing(entity, hand) || isSwinging(entity, hand)) {
            return false;
        }
        return matchesItem(entity, item(entity, hand), selector, ItemAction.HOLD, hand);
    }

    static boolean use(LivingEntity entity, String handName, String selector) {
        InteractionHand hand = hand(handName);
        return hand != null && isUsing(entity, hand)
                && matchesItem(entity, item(entity, hand), selector, ItemAction.USE, hand);
    }

    static boolean swing(LivingEntity entity, String handName, String selector) {
        InteractionHand hand = hand(handName);
        return hand != null && isSwinging(entity, hand)
                && matchesItem(entity, item(entity, hand), selector, ItemAction.SWING, hand);
    }

    static boolean armor(LivingEntity entity, String slotName, String selector) {
        EquipmentSlot slot = armorSlot(slotName);
        return slot != null && matchesItem(entity, entity.getItemBySlot(slot), selector,
                ItemAction.HOLD, null);
    }

    static boolean ride(LivingEntity entity, String relation, String selector) {
        if (relation == null) {
            return false;
        }
        if (relation.equalsIgnoreCase("vehicle")) {
            return matchesEntity(entity.getVehicle(), selector);
        }
        if (relation.equalsIgnoreCase("passenger")) {
            for (Entity passenger : entity.getPassengers()) {
                if (matchesEntity(passenger, selector)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isUsing(LivingEntity entity, InteractionHand hand) {
        return entity.isUsingItem() && entity.getUsedItemHand() == hand;
    }

    static boolean isSwinging(LivingEntity entity, InteractionHand hand) {
        return swingSignal(entity, hand).active();
    }

    /**
     * Detects both vanilla hand swings and Epic Fight attack animations.
     *
     * <p>Epic Fight drives combat attacks through its animator and does not have to keep
     * {@link LivingEntity#swinging} active. Reading the current public animation layer keeps
     * YSM {@code swing:*} timelines aligned with the combat animation without replacing the
     * Epic Fight body pose.</p>
     */
    static SwingSignal swingSignal(LivingEntity entity, InteractionHand hand) {
        if (entity == null || hand == null) {
            return SwingSignal.INACTIVE;
        }
        SwingSignal epicFight = epicFightSwing(entity, hand);
        if (epicFight.active()) {
            return epicFight;
        }
        if (entity.swinging && entity.swingingArm == hand) {
            return new SwingSignal(true, "minecraft", entity.swingTime);
        }
        return SwingSignal.INACTIVE;
    }

    static ItemStack item(LivingEntity entity, InteractionHand hand) {
        return hand == InteractionHand.MAIN_HAND
                ? entity.getMainHandItem() : entity.getOffhandItem();
    }

    static String itemToken(ItemStack stack) {
        if (stack.isEmpty()) {
            return "empty";
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "unknown" : id.toString();
    }

    static List<String> categories(LivingEntity entity, ItemStack stack,
                                   ItemAction action, InteractionHand hand) {
        List<String> result = new ArrayList<>();
        if (stack.isEmpty()) {
            if (action == ItemAction.HOLD) {
                result.add("empty");
            }
            return result;
        }
        if (action == ItemAction.USE) {
            if (stack.getItem() instanceof ShieldItem) {
                result.add("shield");
            }
            addUseCategory(result, stack.getUseAnimation());
            return List.copyOf(result);
        }
        if (action == ItemAction.HOLD) {
            if (stack.getItem() instanceof CrossbowItem) {
                result.add(CrossbowItem.isCharged(stack) ? "charged_crossbow" : "crossbow");
            }
            if (stack.getItem() instanceof FishingRodItem) {
                if (hand == InteractionHand.MAIN_HAND && entity instanceof Player player
                        && player.fishing != null) {
                    result.add("fishing");
                }
                result.add("fishing_rod");
            }
        } else if (stack.getItem() instanceof FishingRodItem) {
            result.add("fishing_rod");
        }
        if (stack.getItem() instanceof SwordItem) {
            result.add("sword");
        } else if (stack.getItem() instanceof AxeItem) {
            result.add("axe");
        } else if (stack.getItem() instanceof PickaxeItem) {
            result.add("pickaxe");
        } else if (stack.getItem() instanceof ShovelItem) {
            result.add("shovel");
        } else if (stack.getItem() instanceof HoeItem) {
            result.add("hoe");
        } else if (stack.getItem() instanceof ShieldItem) {
            result.add("shield");
        } else if (stack.getItem() instanceof ThrowablePotionItem) {
            result.add("throwable_potion");
        }
        // Official YSM also uses swing:bow and swing:spear. A swing condition still
        // needs the item's use-animation family even though the item is not currently
        // in its use action.
        addUseCategory(result, stack.getUseAnimation());
        return List.copyOf(result);
    }

    private static SwingSignal epicFightSwing(LivingEntity entity,
                                                InteractionHand requestedHand) {
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(
                entity, LivingEntityPatch.class);
        if (patch == null) {
            return SwingSignal.INACTIVE;
        }
        ClientAnimator animator = patch.getClientAnimator();
        if (animator == null) {
            return SwingSignal.INACTIVE;
        }
        SwingSignal[] result = {SwingSignal.INACTIVE};
        animator.iterVisibleLayersUntilFalse(layer -> {
            AnimationPlayer player = layer.animationPlayer;
            if (player == null || player.isEmpty()) {
                return true;
            }
            DynamicAnimation current = player.getAnimation().get();
            InteractionHand actionHand;
            if (current instanceof AttackAnimation attack) {
                float elapsed = player.getElapsedTime();
                AttackAnimation.Phase phase = attack.getPhaseByTime(elapsed);
                actionHand = phase == null ? InteractionHand.MAIN_HAND : phase.getHand();
            } else if (current instanceof ReboundAnimation) {
                // Releasing a bow does not set vanilla's hand-swing state and official
                // YSM therefore returns from use_mainhand:bow to its hold clip. Mapping
                // Epic Fight's rebound to swing:bow instead starts model-authored melee
                // attacks (magic circles, lunges, and root motion) after every arrow.
                if (!shouldTreatReboundAsSwing(
                        item(entity, requestedHand).getUseAnimation())) {
                    return true;
                }
                actionHand = entity.getUsedItemHand();
            } else {
                return true;
            }
            float elapsed = player.getElapsedTime();
            if (actionHand != requestedHand) {
                return true;
            }
            StaticAnimation action = (StaticAnimation) current;
            String source = action.getRegistryName() == null
                    ? action.getClass().getName() + '@' + action.getId()
                    : action.getRegistryName().toString();
            result[0] = new SwingSignal(true, "epicfight:" + source, elapsed);
            return false;
        });
        return result[0];
    }

    static boolean shouldTreatReboundAsSwing(UseAnim useAnimation) {
        return useAnimation != UseAnim.BOW;
    }

    static boolean matchesItem(LivingEntity entity, ItemStack stack, String selector,
                               ItemAction action, InteractionHand hand) {
        if (selector == null || selector.length() < 2) {
            return false;
        }
        char kind = selector.charAt(0);
        String value = selector.substring(1).toLowerCase(Locale.ROOT);
        if (kind == ':') {
            if (value.equals("default")) {
                return !stack.isEmpty();
            }
            return categories(entity, stack, action, hand).contains(value);
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null || stack.isEmpty()) {
            return false;
        }
        if (kind == '$') {
            return id.equals(BuiltInRegistries.ITEM.getKey(stack.getItem()));
        }
        return kind == '#' && stack.is(TagKey.create(Registries.ITEM, id));
    }

    static boolean matchesEntity(Entity entity, String selector) {
        if (entity == null || selector == null || selector.length() < 2) {
            return false;
        }
        char kind = selector.charAt(0);
        ResourceLocation id = ResourceLocation.tryParse(
                selector.substring(1).toLowerCase(Locale.ROOT));
        if (id == null) {
            return false;
        }
        if (kind == '$') {
            return id.equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        }
        return kind == '#' && entity.getType().is(
                TagKey.create(Registries.ENTITY_TYPE, id));
    }

    private static void addUseCategory(List<String> target, UseAnim useAnimation) {
        String category = switch (useAnimation) {
            case EAT -> "eat";
            case DRINK -> "drink";
            case BLOCK -> "block";
            case BOW -> "bow";
            case SPEAR -> "spear";
            case CROSSBOW -> "crossbow";
            case SPYGLASS -> "spyglass";
            case TOOT_HORN -> "toot_horn";
            case BRUSH -> "brush";
            default -> null;
        };
        if (category != null && !target.contains(category)) {
            target.add(category);
        }
    }

    private static InteractionHand hand(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "mainhand", "main_hand" -> InteractionHand.MAIN_HAND;
            case "offhand", "off_hand" -> InteractionHand.OFF_HAND;
            default -> null;
        };
    }

    private static EquipmentSlot armorSlot(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "head" -> EquipmentSlot.HEAD;
            case "chest" -> EquipmentSlot.CHEST;
            case "legs" -> EquipmentSlot.LEGS;
            case "feet" -> EquipmentSlot.FEET;
            default -> null;
        };
    }
}
