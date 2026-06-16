package org.qualet.irlredactor;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IRLRedactorMod implements ModInitializer
{
    public static final String MOD_ID = "irl-redactor";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize()
    {
        LOGGER.info("IRL Redactor stub loaded.");
    }
}
