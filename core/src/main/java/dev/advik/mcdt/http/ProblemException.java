package dev.advik.mcdt.http;

import java.util.Map;

/** Thrown anywhere in request handling to produce a problem-JSON response. */
public final class ProblemException extends RuntimeException {
	private final Problem problem;
	private final Map<String, String> headers;

	public ProblemException(Problem problem) {
		this(problem, Map.of());
	}

	public ProblemException(Problem problem, Map<String, String> headers) {
		super(problem.type() + ": " + problem.detail(), null, false, false);
		this.problem = problem;
		this.headers = Map.copyOf(headers);
	}

	public Problem problem() {
		return problem;
	}

	public Map<String, String> headers() {
		return headers;
	}
}
