package dev.advik.mcdt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared constants for MCDT. */
public final class Mcdt {
	public static final String MOD_ID = "mcdt";
	public static final String NAME = "Minecraft DevTools";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private Mcdt() {
	}
}
