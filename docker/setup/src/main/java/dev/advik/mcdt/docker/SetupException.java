package dev.advik.mcdt.docker;

/** A setup failure with a message for the container log. The container stops with exit code 1. */
public final class SetupException extends RuntimeException {
	public SetupException(String message) {
		super(message);
	}

	public SetupException(String message, Throwable cause) {
		super(message, cause);
	}
}
