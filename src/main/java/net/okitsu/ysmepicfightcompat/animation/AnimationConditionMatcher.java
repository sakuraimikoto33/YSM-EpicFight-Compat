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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Public-Minecraft-API condition matching shared by hardcoded clips and Molang ctrl calls. */
final class AnimationConditionMatcher {
    enum ItemAction {
        HOLD,
        USE,
        SWING
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
        return entity.swinging && entity.swingingArm == hand;
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
        if (action == ItemAction.HOLD) {
            addUseCategory(result, stack.getUseAnimation());
        }
        return List.copyOf(result);
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
