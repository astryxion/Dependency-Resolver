package com.dependencyresolver.forge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * The entry Forge shows in the mods list. Dependency repair already finished
 * in {@code EarlyDependencyService} before this constructor can run.
 */
@Mod(DependencyResolverMod.MOD_ID)
public final class DependencyResolverMod {
    public static final String MOD_ID = "dependencyresolver";
    private static final Logger LOGGER = LogUtils.getLogger();

    public DependencyResolverMod(FMLJavaModLoadingContext context) {
        LOGGER.info("Dependency Resolver is active.");
    }
}
