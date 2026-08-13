package net.okitsu.ysmepicfightcompat;

import net.minecraftforge.fml.common.Mod;
import net.okitsu.ysmepicfightcompat.config.ClientPreferences;
import net.okitsu.ysmepicfightcompat.network.CompatNetwork;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Forge entry point for the official YSM to Epic Fight bridge. */
@Mod(CompatMod.MOD_ID)
public final class CompatMod {
    public static final String MOD_ID = "ysm_epicfight_compat";
    public static final Logger LOG = LogManager.getLogger("YSM-EF Compat");

    public CompatMod() {
        ClientPreferences.register();
        CompatNetwork.registerMessages();
        LOG.info("YSM-EF Compat: initialized");
    }
}
