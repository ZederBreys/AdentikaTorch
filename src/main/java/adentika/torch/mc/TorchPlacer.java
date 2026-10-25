package adentika.torch.mc;

import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Places one torch with ordinary client interactions (M4, BLOCK_CLASSIFICATION §1.1), modelled on how Baritone's
 * farm process clicks (docs/TECHNICAL_RESEARCH.md §9):
 * <ul>
 *   <li>reach: a ray from the eyes along the exact look direction, as long as the player's real block interaction
 *       range ({@code getBlockInteractionRange()}) minus a margin, must hit the support face — nothing is ever
 *       clicked beyond arm's length or through blocks;</li>
 *   <li>the look direction is set before the movement packet of the tick, the click uses the hit of that ray
 *       (what a vanilla client would send), via {@code interactionManager.interactBlock};</li>
 *   <li>torch from the hotbar/offhand only; sneaking is pressed before the click when the support is interactive.</li>
 * </ul>
 * Whether the torch really stands is judged later by {@link adentika.torch.core.PlacementTracker}.
 */
public final class TorchPlacer {
    /** Safety margin below the player's reach, in blocks. */
    public static final double REACH_MARGIN = 0.1;

    /** Torch cell and the direction of its supporting block ({@link Direction#DOWN} = floor torch). */
    public record Target(BlockPos cell, Direction support) {
        public BlockPos supportPos() {
            return cell.offset(support);
        }

        /** Face of the support block that the torch is attached to. */
        public Direction face() {
            return support.getOpposite();
        }

        /** Centre of the support face: where the player looks. */
        public Vec3d faceCenter() {
            Direction f = face();
            return Vec3d.ofCenter(supportPos()).add(f.getOffsetX() * 0.5, f.getOffsetY() * 0.5, f.getOffsetZ() * 0.5);
        }

        BlockState torchState() {
            return support == Direction.DOWN ? Blocks.TORCH.getDefaultState()
                : Blocks.WALL_TORCH.getDefaultState().with(WallTorchBlock.FACING, face());
        }
    }

    public enum Click { CLICKED, NOT_NOW, NO_TORCHES }

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private Target target;
    private boolean pressedSneak;

    public Target target() {
        return target;
    }

    public boolean prepared() {
        return target != null;
    }

    public static boolean hasTorch() {
        return mc.player != null && InvUtils.findInHotbar(Items.TORCH).found();
    }

    /** Reach limit actually used: the player's block interaction range minus {@link #REACH_MARGIN}. */
    public static double reach() {
        return mc.player.getBlockInteractionRange() - REACH_MARGIN;
    }

    /**
     * The game's own placement rules plus reach and line of sight, for the look direction towards the face centre.
     * Null if the torch cannot be placed from where the player stands now.
     */
    public static BlockHitResult aim(Target t) {
        if (mc.player == null || mc.world == null || mc.currentScreen != null || !mc.player.isAlive()) return null;
        BlockState cell = mc.world.getBlockState(t.cell());
        if (!cell.isReplaceable() || !cell.getFluidState().isEmpty() || !t.torchState().canPlaceAt(mc.world, t.cell())) return null;
        Vec3d center = t.faceCenter();
        return ray(t, (float) Rotations.getYaw(center), (float) Rotations.getPitch(center));
    }

    /** Ray from the eyes along (yaw, pitch), limited to the reach; must hit the support block on the torch face. */
    private static BlockHitResult ray(Target t, float yaw, float pitch) {
        Vec3d eye = mc.player.getEyePos();
        double range = reach();
        if (eye.squaredDistanceTo(t.faceCenter()) > range * range) return null;
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(range));
        // Collider shapes: grass and flowers in the torch cell do not block the view, blocks do.
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
            RaycastContext.FluidHandling.NONE, mc.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(t.supportPos()) || hit.getSide() != t.face()) return null;
        return eye.squaredDistanceTo(hit.getPos()) <= range * range ? hit : null;
    }

    /** Turns the head to the target (visible) and presses sneak if needed. The click comes later. */
    public boolean prepare(Target t, boolean sneak) {
        if (aim(t) == null || !hasTorch()) return false;
        target = t;
        lookAtTarget();
        if (sneak && !mc.options.sneakKey.isPressed()) {
            mc.options.sneakKey.setPressed(true);
            pressedSneak = true;
        }
        return true;
    }

    public boolean sneakPressed() {
        return pressedSneak;
    }

    /** Keeps looking at the target while waiting (the player's mouse may have moved). */
    public void lookAtTarget() {
        if (target == null || mc.player == null) return;
        Vec3d c = target.faceCenter();
        mc.player.setYaw((float) Rotations.getYaw(c));
        mc.player.setPitch((float) Rotations.getPitch(c));
    }

    /**
     * Right-clicks along the direction the player looks at now (already sent to the server this tick), only if that
     * ray hits the support face within reach. Ends the preparation either way.
     */
    public Click click() {
        Target t = target;
        if (t == null || mc.player == null) return Click.NOT_NOW;
        try {
            BlockState cell = mc.world.getBlockState(t.cell());
            if (!cell.isReplaceable() || !t.torchState().canPlaceAt(mc.world, t.cell())) return Click.NOT_NOW;
            BlockHitResult hit = ray(t, mc.player.getYaw(), mc.player.getPitch());
            if (hit == null) return Click.NOT_NOW;

            FindItemResult torch = InvUtils.findInHotbar(Items.TORCH);
            if (!torch.found()) return Click.NO_TORCHES;
            Hand hand = torch.isOffhand() ? Hand.OFF_HAND : Hand.MAIN_HAND;
            boolean swapped = false;
            if (hand == Hand.MAIN_HAND && !torch.isMainHand()) {
                InvUtils.swap(torch.slot(), true);
                swapped = true;
            }
            ActionResult result = mc.interactionManager.interactBlock(mc.player, hand, hit);
            if (result.shouldSwingHand()) mc.player.swingHand(hand);
            if (swapped) InvUtils.swapBack();
            return Click.CLICKED;
        } finally {
            releaseSneak();
            target = null;
        }
    }

    /** Immediate stop: releases sneak if we pressed it. */
    public void cancel() {
        releaseSneak();
        target = null;
    }

    private void releaseSneak() {
        if (!pressedSneak) return;
        // Keep it pressed if the player is physically holding the key.
        InputUtil.Key key = InputUtil.fromTranslationKey(mc.options.sneakKey.getBoundKeyTranslationKey());
        boolean held = key.getCategory() == InputUtil.Type.KEYSYM && InputUtil.isKeyPressed(mc.getWindow().getHandle(), key.getCode());
        mc.options.sneakKey.setPressed(held);
        pressedSneak = false;
    }
}
