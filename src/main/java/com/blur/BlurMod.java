package com.blur;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint. Blur is 100% client-side (see "environment": "client" in
 * fabric.mod.json) so there is nothing to do here besides log that we loaded.
 * All real logic lives in {@link com.blur.client.BlurClient}.
 */
public class BlurMod implements ModInitializer {
	public static final String MOD_ID = "blur";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("Blur loaded.");
	}
}
