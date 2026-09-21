package ru.evga314.dragscroll;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DragScrollClient implements ClientModInitializer {
	public static final String MOD_ID = "dragscroll";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		DragScrollConfig.load();
		LOGGER.info("Drag Scroll 1.1.11-debug2 initialized. Drag threshold: {} px", DragScrollConfig.getDragThreshold());
	}
}
