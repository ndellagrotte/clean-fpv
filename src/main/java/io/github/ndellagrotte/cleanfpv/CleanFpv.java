package io.github.ndellagrotte.cleanfpv;

import io.github.ndellagrotte.cleanfpv.proxy.CommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

/**
 * Mod entry point. All work is delegated to the sided proxy; see {@link CommonProxy} and
 * {@code proxy.ClientProxy} for the handler-registration points.
 *
 * <p>{@code acceptableRemoteVersions = "*"}: clients may join servers without the mod (arming is
 * then refused because no Hello arrives, PLAN §8) and vice versa.
 */
@Mod(modid = Reference.MOD_ID, name = Reference.MOD_NAME, version = Reference.VERSION,
        acceptableRemoteVersions = "*")
public class CleanFpv {

    public static final Logger LOGGER = LogManager.getLogger(Reference.MOD_NAME);

    @SidedProxy(clientSide = "io.github.ndellagrotte.cleanfpv.proxy.ClientProxy",
            serverSide = "io.github.ndellagrotte.cleanfpv.proxy.CommonProxy")
    public static CommonProxy proxy;

    /** {@code config/cleanfpv/}; set in preInit (the settings store lives here). */
    private static File configDir;

    public static File configDir() {
        return configDir;
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} {} loading", Reference.MOD_NAME, Reference.VERSION);
        configDir = new File(event.getModConfigurationDirectory(), Reference.MOD_ID);
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        proxy.serverStopped(event);
    }
}
