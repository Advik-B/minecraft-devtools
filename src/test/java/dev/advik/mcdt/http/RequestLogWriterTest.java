package dev.advik.mcdt.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestLogWriterTest {

	@Test
	void testLogEntryWritingAndFlush(@TempDir Path tempDir) throws Exception {
		try (RequestLogWriter writer = new RequestLogWriter(tempDir)) {
			RequestLogWriter.Entry entry = new RequestLogWriter.Entry(
					Instant.now(),
					"GET",
					"/v1/health",
					200,
					5.2,
					10,
					null,
					"127.0.0.1"
			);

			writer.log(entry);
			assertEquals(0, writer.dropped());

			// Wait up to 2 seconds for background flush
			long deadline = System.currentTimeMillis() + 2000;
			boolean fileFound = false;
			while (System.currentTimeMillis() < deadline) {
				try (var stream = Files.list(tempDir)) {
					List<Path> files = stream.toList();
					if (!files.isEmpty() && Files.size(files.get(0)) > 0) {
						fileFound = true;
						String content = Files.readString(files.get(0));
						assertTrue(content.contains("\"path\":\"/v1/health\""));
						assertTrue(content.contains("\"status\":200"));
						break;
					}
				}
				Thread.sleep(50);
			}

			assertTrue(fileFound, "Log file should be written with the request entry");
		}
	}
}
