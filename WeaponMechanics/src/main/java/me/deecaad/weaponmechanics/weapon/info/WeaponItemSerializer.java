package me.deecaad.weaponmechanics.weapon.info;

import me.deecaad.core.file.serializers.ItemSerializer;
import me.deecaad.weaponmechanics.WeaponMechanics;
import me.deecaad.weaponmechanics.utils.CustomTag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.io.File;

/**
 * Simple class to handle weapon item serialization a bit differently.
 */
public class WeaponItemSerializer extends ItemSerializer {

    @Override
    public String getKeyword() {
        return "Weapon_Item";
    }

    @Override
    public ItemStack serialize(File file, ConfigurationSection configurationSection, String path) {
        ItemStack weaponStack = super.serializeWithoutRecipe(file, configurationSection, path);
        if (weaponStack == null) return null;
        String weaponTitle = path.split("\\.")[0];
        WeaponMechanics.getWeaponHandler().getInfoHandler().addWeapon(weaponTitle);

        int magazineSize = configurationSection.getInt(weaponTitle + ".Reload.Magazine_Size", -1);
        if (magazineSize != -1) {
            CustomTag.AMMO_LEFT.setInteger(weaponStack, magazineSize);
        }

        CustomTag.WEAPON_TITLE.setString(weaponStack, weaponTitle);
        weaponStack = super.serializeRecipe(file, configurationSection, path, weaponStack);
        return weaponStack;
    }
}