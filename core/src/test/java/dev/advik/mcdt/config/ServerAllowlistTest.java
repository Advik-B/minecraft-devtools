package dev.advik.mcdt.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerAllowlistTest {

	@Test
	void exactEntriesMatchCaseInsensitively() {
		List<String> entries = List.of("mc.example.com");
		assertTrue(ServerAllowlist.allows(entries, "MC.Example.com"));
		assertFalse(ServerAllowlist.allows(entries, "mc.example.com:25566"));
		assertFalse(ServerAllowlist.allows(entries, "other.example.com"));
		assertFalse(ServerAllowlist.allows(entries, null));
	}

	@Test
	void wildcardPatternsMatchAnyRunOfCharacters() {
		List<String> entries = List.of("*.example.com", "play.*:25565");
		assertTrue(ServerAllowlist.allows(entries, "eu.example.com"));
		assertTrue(ServerAllowlist.allows(entries, "a.b.EXAMPLE.com"));
		assertTrue(ServerAllowlist.allows(entries, "play.foo.net:25565"));
		assertFalse(ServerAllowlist.allows(entries, "example.com"));
		assertFalse(ServerAllowlist.allows(entries, "eu.example.com.evil.net"));
		assertFalse(ServerAllowlist.allows(entries, "play.foo.net:25566"));
		assertFalse(ServerAllowlist.allows(entries, null));
	}

	@Test
	void regexCharactersInEntriesAreLiteral() {
		List<String> entries = List.of("mc.example.com", "a+b.*");
		assertFalse(ServerAllowlist.allows(entries, "mcXexampleXcom"));
		assertTrue(ServerAllowlist.allows(entries, "a+b.net"));
		assertFalse(ServerAllowlist.allows(entries, "aab.net"));
	}

	@Test
	void starAloneAllowsEveryServer() {
		List<String> entries = List.of(" * ");
		assertTrue(ServerAllowlist.allows(entries, "anything.net:1234"));
		assertTrue(ServerAllowlist.allows(entries, null));
	}

	@Test
	void reportsWildcardEntries() {
		assertEquals(List.of("*", "*.example.com"),
				ServerAllowlist.wildcards(List.of("mc.example.com", "*", "*.example.com")));
		assertTrue(ServerAllowlist.wildcards(List.of("mc.example.com")).isEmpty());
	}

	@Test
	void distinguishesWildcardOnlyMatches() {
		List<String> entries = List.of("mc.example.com", "*.example.com");
		assertFalse(ServerAllowlist.allowedOnlyByWildcard(entries, "mc.example.com"));
		assertTrue(ServerAllowlist.allowedOnlyByWildcard(entries, "eu.example.com"));
		assertFalse(ServerAllowlist.allowedOnlyByWildcard(entries, "other.net"));
	}
}
