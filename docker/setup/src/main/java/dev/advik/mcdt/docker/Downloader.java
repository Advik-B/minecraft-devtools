package dev.advik.mcdt.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/** HTTP for setup: JSON reads and file downloads, with an optional SHA-512 check. */
final class Downloader {
	static final String USER_AGENT = "Advik-B/minecraft-devtools (mcdt-docker)";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final HttpClient client = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(30))
			.build();

	/** GETs a JSON document. A 404 returns null. */
	JsonNode getJson(URI uri) {
		HttpResponse<InputStream> response = send(uri);
		try (InputStream body = response.body()) {
			if (response.statusCode() == 404) {
				return null;
			}
			if (response.statusCode() != 200) {
				throw new SetupException("GET " + uri + " returned HTTP " + response.statusCode() + ".");
			}
			return MAPPER.readTree(body);
		} catch (IOException e) {
			throw new SetupException("GET " + uri + " failed: " + e.getMessage(), e);
		}
	}

	/**
	 * Downloads {@code uri} to {@code target}. If {@code sha512} is not null, the file must match it.
	 * The file is written to a temporary name first, so a failed download never leaves a partial jar.
	 */
	void download(URI uri, Path target, String sha512) {
		HttpResponse<InputStream> response = send(uri);
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) {
				throw new SetupException("Download of " + uri + " returned HTTP " + response.statusCode() + ".");
			}
			Files.createDirectories(target.getParent());
			Files.copy(body, tmp, StandardCopyOption.REPLACE_EXISTING);
			if (sha512 != null) {
				String actual = sha512(tmp);
				if (!actual.equalsIgnoreCase(sha512)) {
					throw new SetupException("SHA-512 mismatch for " + uri + ": expected " + sha512 + ", got " + actual + ".");
				}
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new SetupException("Download of " + uri + " failed: " + e.getMessage(), e);
		} finally {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// Best effort: the next start overwrites it.
			}
		}
	}

	static String sha512(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-512");
			byte[] buffer = new byte[64 * 1024];
			for (int n; (n = in.read(buffer)) > 0; ) {
				digest.update(buffer, 0, n);
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private HttpResponse<InputStream> send(URI uri) {
		HttpRequest request = HttpRequest.newBuilder(uri)
				.header("User-Agent", USER_AGENT)
				.timeout(Duration.ofMinutes(5))
				.GET()
				.build();
		try {
			return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
		} catch (IOException e) {
			throw new SetupException("GET " + uri + " failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new SetupException("GET " + uri + " was interrupted.", e);
		}
	}
}
