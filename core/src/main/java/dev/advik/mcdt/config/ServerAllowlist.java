package dev.advik.mcdt.config;

import dev.advik.mcdt.Mcdt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matches multiplayer server addresses against {@link McdtConfig#allowedMultiplayerServers}. Entries are matched
 * case-insensitively against the full address as typed in the server list (host, plus {@code :port} if one was
 * given). {@code *} in an entry matches any run of characters, and an entry of just {@code *} allows every server.
 */
public final class ServerAllowlist {
	/** The entry that allows act and reflect-write actions on any multiplayer server. */
	public static final String ANY_SERVER = "*";

	private static final String BANNER = "#".repeat(100);

	private ServerAllowlist() {
	}

	/** Whether {@code address} is allowed. An {@code *} entry allows every server, even an unknown address. */
	public static boolean allows(List<String> entries, String address) {
		for (String entry : entries) {
			String pattern = entry.trim();
			if (ANY_SERVER.equals(pattern)) {
				return true;
			}
			if (address != null && matches(pattern, address.trim())) {
				return true;
			}
		}
		return false;
	}

	/** Whether {@code address} is allowed only by a wildcard entry, not by an exact one. */
	public static boolean allowedOnlyByWildcard(List<String> entries, String address) {
		boolean exact = address != null && entries.stream().anyMatch(e -> e.trim().equalsIgnoreCase(address.trim()));
		return !exact && allows(entries, address);
	}

	/** The entries that contain {@code *}. */
	public static List<String> wildcards(List<String> entries) {
		List<String> wildcards = new ArrayList<>();
		for (String entry : entries) {
			if (entry.contains("*")) {
				wildcards.add(entry.trim());
			}
		}
		return wildcards;
	}

	static boolean matches(String pattern, String address) {
		if (!pattern.contains("*")) {
			return pattern.equalsIgnoreCase(address);
		}
		String[] parts = pattern.split("\\*", -1);
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) {
				regex.append(".*");
			}
			if (!parts[i].isEmpty()) {
				regex.append(Pattern.quote(parts[i].toLowerCase(Locale.ROOT)));
			}
		}
		return Pattern.matches(regex.toString(), address.toLowerCase(Locale.ROOT));
	}

	/** Logs a loud warning if any entry is a wildcard. Call it whenever the allowlist is loaded or changed. */
	public static void warnIfWildcard(List<String> entries) {
		List<String> wildcards = wildcards(entries);
		if (wildcards.isEmpty()) {
			return;
		}
		boolean anyServer = wildcards.contains(ANY_SERVER);
		Mcdt.LOGGER.warn(BANNER);
		Mcdt.LOGGER.warn("!!! WARNING: WILDCARD MULTIPLAYER SERVER ALLOW IS ENABLED !!!");
		Mcdt.LOGGER.warn("!!! THERE IS A HUGE BAN RISK FOR THIS MINECRAFT ACCOUNT !!!");
		Mcdt.LOGGER.warn("allowedMultiplayerServers contains wildcard entries: {}", wildcards);
		if (anyServer) {
			Mcdt.LOGGER.warn("'*' lets API clients send input, chat, Baritone jobs, reflection writes and raw packets"
					+ " on ANY multiplayer server this client joins.");
		} else {
			Mcdt.LOGGER.warn("API clients can send input, chat, Baritone jobs, reflection writes and raw packets"
					+ " on EVERY server that matches these patterns, not just ones you listed by name.");
		}
		Mcdt.LOGGER.warn("Most servers ban automated clients and many run anti-cheat that will flag this traffic.");
		Mcdt.LOGGER.warn("Remove the wildcard from allowedMultiplayerServers (or MCDT_ALLOWED_MULTIPLAYER_SERVERS)"
				+ " unless you own or have permission to automate every matching server.");
		Mcdt.LOGGER.warn(BANNER);
	}

	/** Logs a loud warning when the client joins a server that only a wildcard entry allows. */
	public static void warnOnJoin(List<String> entries, String address) {
		if (!allowedOnlyByWildcard(entries, address)) {
			return;
		}
		Mcdt.LOGGER.warn(BANNER);
		Mcdt.LOGGER.warn("!!! WARNING: joined multiplayer server '{}', which is allowed ONLY by a wildcard entry {} !!!",
				address != null ? address : "unknown", wildcards(entries));
		Mcdt.LOGGER.warn("!!! API automation is ACTIVE on this server. THERE IS A HUGE BAN RISK FOR THIS ACCOUNT !!!");
		Mcdt.LOGGER.warn(BANNER);
	}
}
