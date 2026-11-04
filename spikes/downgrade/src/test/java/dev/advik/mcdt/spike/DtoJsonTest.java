package dev.advik.mcdt.spike;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.advik.mcdt.api.v1.dto.ChatDto;
import dev.advik.mcdt.api.v1.dto.PlayerLookDto;
import dev.advik.mcdt.json.Json;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Records keep their JSON shape and value semantics after being downgraded to Java 8 bytecode. */
class DtoJsonTest {
	@Test
	void recordSerializesByComponentName() throws Exception {
		String json = Json.MAPPER.writeValueAsString(new ChatDto.SendResponse(true, "chat", "hi", 42L));
		assertEquals("{\"sent\":true,\"type\":\"chat\",\"content\":\"hi\",\"tick\":42}", json);
	}

	@Test
	void recordDeserializesThroughCanonicalConstructor() throws Exception {
		ChatDto.SendRequest request = Json.MAPPER.readValue("{\"message\":\"hi\",\"extra\":1}", ChatDto.SendRequest.class);
		assertEquals("hi", request.message());
		assertNull(request.command());
		assertEquals(new ChatDto.SendRequest("hi", null), request);
	}

	@Test
	void nestedRecordsRoundTrip() throws Exception {
		ChatDto.HistoryResponse original = new ChatDto.HistoryResponse(2, 0, 50, List.of(
			new ChatDto.MessageEntry("a", "{\"text\":\"a\"}", 1),
			new ChatDto.MessageEntry("b", "{\"text\":\"b\"}", 2)));
		String json = Json.MAPPER.writeValueAsString(original);
		assertEquals(original, Json.MAPPER.readValue(json, ChatDto.HistoryResponse.class));
		Map<String, Object> asMap = Json.MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
		assertEquals(List.of("total", "offset", "limit", "messages"), List.copyOf(asMap.keySet()));
	}

	@Test
	void arrayAndBoxedComponents() throws Exception {
		PlayerLookDto.Request request = Json.MAPPER.readValue(
			"{\"yaw\":90.5,\"target\":[1.0,2.0,3.0]}", PlayerLookDto.Request.class);
		assertEquals(90.5f, request.yaw());
		assertNull(request.pitch());
		assertArrayEquals(new double[] {1.0, 2.0, 3.0}, request.target());
	}

	@Test
	void recordValueSemantics() {
		ChatDto.SendResponse a = new ChatDto.SendResponse(true, "chat", "hi", 1L);
		ChatDto.SendResponse b = new ChatDto.SendResponse(true, "chat", "hi", 1L);
		assertEquals(a, b);
		assertEquals(a.hashCode(), b.hashCode());
		// JvmDowngrader prints the binary name ("ChatDto$SendResponse[...]"); Java prints the simple name.
		assertTrue(a.toString().endsWith("SendResponse[sent=true, type=chat, content=hi, tick=1]"), a.toString());
	}
}
