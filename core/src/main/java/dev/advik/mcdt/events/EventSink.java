package dev.advik.mcdt.events;

/** One connected event-stream client, independent of the transport (WebSocket today). */
public interface EventSink {
	/** Stable id of the connection, unique among open connections. */
	String id();

	/** Sends one text frame. Called from the subscriber's sender thread. */
	void send(String message) throws Exception;

	/** Closes the connection with a WebSocket-style status code. */
	void close(int statusCode, String reason);
}
