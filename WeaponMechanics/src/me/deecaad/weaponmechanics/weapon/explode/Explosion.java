package me.deecaad.weaponmechanics.weapon.explode;

import me.deecaad.compatibility.CompatibilityAPI;
import me.deecaad.core.utils.LogLevel;
import me.deecaad.core.utils.MaterialHelper;
import me.deecaad.core.utils.StringUtils;
import me.deecaad.core.utils.VectorUtils;
import me.deecaad.weaponmechanics.weapon.damage.BlockDamageData;
import me.deecaad.weaponmechanics.weapon.damage.DamageHandler;
import me.deecaad.weaponmechanics.weapon.explode.regeneration.BlockRegenSorter;
import me.deecaad.weaponmechanics.weapon.explode.regeneration.LayerDistanceSorter;
import me.deecaad.weaponmechanics.weapon.explode.regeneration.RegenerationData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static me.deecaad.weaponmechanics.WeaponMechanics.debug;

public class Explosion {

    private static DamageHandler damageHandler = new DamageHandler();

    private final String weaponTitle;
    private final ExplosionShape shape;
    private final ExplosionExposure exposure;
    private final boolean isBreakBlocks;
    private final RegenerationData regeneration;
    private final boolean isBlacklist;
    private final Set<String> materials;
    private final Set<ExplosionTrigger> triggers;
    private final int delay;
    private final boolean isKnockback;

    public Explosion(@Nullable String weaponTitle,
                     @Nonnull ExplosionShape shape,
                     @Nonnull ExplosionExposure exposure,
                     boolean isBreakBlocks,
                     @Nonnull RegenerationData regeneration,
                     boolean isBlacklist,
                     @Nonnull Set<String> materials,
                     @Nonnull Set<ExplosionTrigger> triggers,
                     @Nonnegative int delay,
                     boolean isKnockback) {

        this.weaponTitle = weaponTitle;
        this.shape = shape;
        this.exposure = exposure;
        this.isBreakBlocks = isBreakBlocks;
        this.regeneration = regeneration;
        this.isBlacklist = isBlacklist;
        this.materials = materials;
        this.triggers = triggers;
        this.delay = delay;
        this.isKnockback = isKnockback;
    }

    public ExplosionShape getShape() {
        return shape;
    }

    public RegenerationData getRegeneration() {
        return regeneration;
    }

    public boolean isBlacklist() {
        return isBlacklist;
    }

    public Set<String> getMaterials() {
        return materials;
    }

    public Set<ExplosionTrigger> getTriggers() {
        return triggers;
    }

    public ExplosionExposure getExposure() {
        return exposure;
    }

    public boolean isBreakBlocks() {
        return isBreakBlocks;
    }

    public int getDelay() {
        return delay;
    }

    /**
     * Triggers the explosion at the given location
     *
     * @param cause Whoever caused the explosion
     * @param origin The center of the explosion
     */
    public void explode(LivingEntity cause, Location origin) {
        debug.log(LogLevel.DEBUG, "Generating a " + shape + " explosion at " + origin.getBlock());

        List<Block> blocks = isBreakBlocks ? shape.getBlocks(origin) : new ArrayList<>();
        Map<LivingEntity, Double> entities = exposure.mapExposures(origin, shape);

        final List<Block> transparent = new ArrayList<>();
        final List<Block> solid = new ArrayList<>();

        for (Block block : blocks) {
            Material type = block.getType();

            if (type.isSolid()) {
                solid.add(block);
            } else if (!MaterialHelper.isAir(type)) {
                transparent.add(block);
            }
        }

        int timeOffset;
        if (regeneration == null) {
            timeOffset = -1;
        } else {
            timeOffset = solid.size() / regeneration.getMaxBlocksPerUpdate() * regeneration.getInterval() + regeneration.getTicksBeforeStart();
        }

        int size = transparent.size();
        for (int i = 0; i < size; i++) {
            Block block = transparent.get(i);

            if (isBlacklisted(block) || BlockDamageData.isBroken(block)) {
                continue;
            }

            // This forces all transparent blocks to regenerate at once.
            // This fixes item sorters breaking and general hopper/redstone stuff
            BlockDamageData.damageBlock(block, 1, 1, true, timeOffset);
        }

        BlockRegenSorter sorter = new LayerDistanceSorter(origin, this);
        try {
            solid.sort(sorter);
        } catch (IllegalArgumentException ex) {
            debug.error("A plugin modified the explosion block sorter with an illegal sorter!",
                    "Please report this error to the developers of that plugin", "Sorter: " + sorter);
            debug.log(LogLevel.ERROR, ex);
        }
        size = solid.size();
        for (int i = 0; i < size; i++) {
            Block block = solid.get(i);

            if (isBlacklisted(block) || BlockDamageData.isBroken(block)) {
                continue;
            }

            int regenTime;
            if (regeneration == null) {
                regenTime = -1;
            } else {
                regenTime = regeneration.getTicksBeforeStart() +
                        (i / regeneration.getMaxBlocksPerUpdate()) * regeneration.getInterval();
            }

            BlockDamageData.damageBlock(block, 1, 1, true, regenTime);
        }

        if (weaponTitle != null) {
            damageHandler.tryUseExplosion(cause, weaponTitle, entities);

            if (isKnockback) {
                Vector originVector = origin.toVector();
                for (Map.Entry<LivingEntity, Double> entry : entities.entrySet()) {

                    LivingEntity entity = entry.getKey();
                    double exposure = entry.getValue();

                    // Normalized vector between the explosion and entity involved
                    Vector between = VectorUtils.setLength(entity.getLocation().toVector().subtract(originVector), exposure);
                    Vector motion = entity.getVelocity().add(between);

                    entity.setVelocity(motion);
                }
            }
        }

        // This occurs during commands
        // /wm test explosion default 5, for example
        else {
            for (Map.Entry<LivingEntity, Double> entry : entities.entrySet()) {
                LivingEntity entity = entry.getKey();
                double impact = entry.getValue();

                entity.sendMessage(StringUtils.color("&cYou suffered " + impact * 100 + "% of the impact"));
            }
        }
    }

    /**
     * Checks if the given block is a blacklisted block based
     * on the <code>Configuration</code>. If a block is blacklisted,
     * then it should not be blown up
     *
     * @param block Checks if <code>block</code> is blacklsited
     * @return true if <code>block</code> cannot be blown up
     */
    public boolean isBlacklisted(Block block) {
        String mat = block.getType().name();
        final boolean isBlacklist = this.isBlacklist == materials.contains(mat);
        final boolean isLegacyBlacklist = CompatibilityAPI.getVersion() < 1.13
                && (this.isBlacklist == materials.contains(mat + ":" + block.getData()));

        return isBlacklist || isLegacyBlacklist;
    }

    public enum ExplosionTrigger {

        /**
         * When the projectile is shot/thrown
         */
        SHOOT,

        /**
         * When the projectile hits a non-air and non-liquid block
         */
        BLOCK,

        /**
         * When the projectile hits an entity
         */
        ENTITIES,

        /**
         * When the projectile hits a liquid
         */
        LIQUID
    }
}
