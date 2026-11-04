package dev.advik.mcdt.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.advik.mcdt.Mcdt;
import dev.advik.mcdt.json.Json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes one JSON line per request to {@code logs/mcdt/requests-YYYY-MM-DD.log} on a background thread, so request
 * threads never block on disk. Headers (and therefore the bearer token) are never logged.
 */
public final class RequestLogWriter implements AutoCloseable {
	private static final Entry POISON = new Entry(null, null, null, 0, 0, 0, null, null);

	private final Path dir;
	private final BlockingQueue<Entry> queue = new ArrayBlockingQueue<>(4_096);
	private final AtomicLong dropped = new AtomicLong();
	private final Thread thread;
	private BufferedWriter writer;
	private LocalDate writerDate;

	public RequestLogWriter(Path dir) {
		this.dir = dir;
		this.thread = new Thread(this::run, "MCDT request log");
		this.thread.setDaemon(true);
		this.thread.start();
	}

	/**
	 * @param time       when the request finished
	 * @param method     HTTP method
	 * @param path       request path, without query string
	 * @param status     response status
	 * @param durationMs total handling time
	 * @param tick       tick the response was read at, or -1
	 * @param problem    problem type if the request failed
	 * @param remote     remote address
	 */
	public record Entry(Instant time, String method, String path, int status, double durationMs, long tick,
						String problem, String remote) {
	}

	public void log(Entry entry) {
		if (!queue.offer(entry)) {
			dropped.incrementAndGet();
		}
	}

	public long dropped() {
		return dropped.get();
	}

	private void run() {
		try {
			while (true) {
				Entry entry = queue.poll(1, TimeUnit.SECONDS);
				if (entry == POISON) {
					break;
				}
				if (entry == null) {
					flush();
					continue;
				}
				write(entry);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			closeWriter();
		}
	}

	private void write(Entry entry) {
		try {
			LocalDate date = LocalDate.ofInstant(entry.time(), ZoneId.systemDefault());
			if (writer == null || !date.equals(writerDate)) {
				closeWriter();
				Files.createDirectories(dir);
				writer = Files.newBufferedWriter(dir.resolve("requests-" + date + ".log"), StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				writerDate = date;
			}
			writer.write(Json.MAPPER.writeValueAsString(entry));
			writer.newLine();
			if (queue.isEmpty()) {
				writer.flush();
			}
		} catch (JsonProcessingException e) {
			Mcdt.LOGGER.debug("Could not serialize request log entry", e);
		} catch (IOException e) {
			Mcdt.LOGGER.warn("Could not write MCDT request log: {}", e.getMessage());
			closeWriter();
		}
	}

	private void flush() {
		if (writer != null) {
			try {
				writer.flush();
			} catch (IOException ignored) {
				// reported on next write
			}
		}
	}

	private void closeWriter() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
				// nothing useful to do
			}
			writer = null;
		}
	}

	@Override
	public void close() {
		queue.clear();
		queue.offer(POISON);
		try {
			thread.join(2_000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
