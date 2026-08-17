package net.okitsu.ysmepicfightcompat.mesh;

import net.okitsu.ysmepicfightcompat.geometry.GeometryDocument;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Resolves official YSM bone conventions to Epic Fight's fixed biped joint layout. */
public final class HumanoidRig {
    /** Epic Fight's 1.20.1 humanoid armature uses joint ids 0 through 19. */
    public static final int EPIC_JOINT_COUNT = 20;
    public static final int ROOT = 0;
    public static final int RIGHT_THIGH = 1;
    public static final int RIGHT_LEG = 2;
    public static final int LEFT_THIGH = 4;
    public static final int LEFT_LEG = 5;
    public static final int TORSO = 7;
    public static final int CHEST = 8;
    public static final int HEAD = 9;
    public static final int RIGHT_ARM = 11;
    public static final int RIGHT_HAND = 12;
    public static final int RIGHT_TOOL = 13;
    public static final int LEFT_ARM = 16;
    public static final int LEFT_HAND = 17;
    public static final int LEFT_TOOL = 18;

    private static final Map<String, Integer> BINDINGS = createBindings();
    private static final Map<String, Integer> MAJOR_BINDINGS = createMajorBindings();

    private HumanoidRig() {
    }

    public static int jointFor(GeometryDocument.Bone bone) {
        GeometryDocument.Bone cursor = bone;
        while (cursor != null) {
            Integer joint = BINDINGS.get(normalize(cursor.name()));
            if (joint != null) {
                return joint;
            }
            cursor = cursor.parent();
        }
        return ROOT;
    }

    public static boolean hasDirectBinding(GeometryDocument.Bone bone) {
        return BINDINGS.containsKey(normalize(bone.name()));
    }

    /**
     * Returns whether this bone is one of the fixed humanoid controls that must remain under
     * Epic Fight's exclusive ownership. Accessory aliases intentionally are not major bones.
     */
    public static boolean isMajorBone(GeometryDocument.Bone bone) {
        return MAJOR_BINDINGS.containsKey(normalize(bone.name()));
    }

    private static Map<String, Integer> createBindings() {
        Map<String, Integer> result = new HashMap<>();
        bind(result, ROOT, "root", "center");
        bind(result, TORSO, "allbody", "body", "waist", "torso", "downbody", "hip",
                "hips", "pelvis", "skirt", "leg");
        bind(result, CHEST, "upbody", "upperbody", "chest", "breast", "boob", "collar",
                "backpack", "cape", "elytra", "elytralocator", "arm");
        bind(result, HEAD, "allhead", "head");
        bind(result, LEFT_ARM, "leftarm", "armleft");
        bind(result, RIGHT_ARM, "rightarm", "armright");
        bind(result, LEFT_HAND, "leftforearm", "forearmleft", "lefthand", "handleft");
        bind(result, RIGHT_HAND, "rightforearm", "forearmright", "righthand", "handright");
        bind(result, LEFT_TOOL, "lefthandlocator", "leftitem", "itemleft");
        bind(result, RIGHT_TOOL, "righthandlocator", "rightitem", "itemright");
        bind(result, LEFT_THIGH, "leftleg", "legleft");
        bind(result, RIGHT_THIGH, "rightleg", "legright");
        bind(result, LEFT_LEG, "leftlowerleg", "lowerlegleft", "leftcalf", "leftfoot", "footleft");
        bind(result, RIGHT_LEG, "rightlowerleg", "lowerlegright", "rightcalf", "rightfoot", "footright");
        return Map.copyOf(result);
    }

    private static Map<String, Integer> createMajorBindings() {
        Map<String, Integer> result = new HashMap<>();
        bind(result, ROOT, "root", "center");
        bind(result, TORSO, "allbody", "body", "waist", "torso", "downbody", "hip",
                "hips", "pelvis", "leg");
        bind(result, CHEST, "upbody", "upperbody", "chest", "arm");
        bind(result, HEAD, "allhead", "head");
        bind(result, LEFT_ARM, "leftarm", "armleft");
        bind(result, RIGHT_ARM, "rightarm", "armright");
        bind(result, LEFT_HAND, "leftforearm", "forearmleft", "lefthand", "handleft");
        bind(result, RIGHT_HAND, "rightforearm", "forearmright", "righthand", "handright");
        bind(result, LEFT_TOOL, "lefthandlocator", "leftitem", "itemleft");
        bind(result, RIGHT_TOOL, "righthandlocator", "rightitem", "itemright");
        bind(result, LEFT_THIGH, "leftleg", "legleft");
        bind(result, RIGHT_THIGH, "rightleg", "legright");
        bind(result, LEFT_LEG, "leftlowerleg", "lowerlegleft", "leftcalf", "leftfoot", "footleft");
        bind(result, RIGHT_LEG,
                "rightlowerleg", "lowerlegright", "rightcalf", "rightfoot", "footright");
        return Map.copyOf(result);
    }

    private static void bind(Map<String, Integer> target, int joint, String... names) {
        for (String name : names) {
            target.put(name, joint);
        }
    }

    private static String normalize(String name) {
        String compact = name.toLowerCase(Locale.ROOT).replace("_", "").replace(" ", "");
        int end = compact.length();
        while (end > 0 && Character.isDigit(compact.charAt(end - 1))) {
            end--;
        }
        return compact.substring(0, end);
    }
}
