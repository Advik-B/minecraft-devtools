package dev.advik.mcdt.baritone;

import dev.advik.mcdt.api.v1.dto.BaritoneDto.JobResponse;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class BaritoneJobManager {
	private static final int MAX_JOBS = 1_000;

	private final Map<String, JobResponse> jobs = Collections.synchronizedMap(
			new LinkedHashMap<>(128, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, JobResponse> eldest) {
					return size() > MAX_JOBS;
				}
			}
	);

	private final AtomicReference<String> activeJobId = new AtomicReference<>();

	public JobResponse createJob(String type, String message) {
		String id = "job_" + UUID.randomUUID().toString().substring(0, 8);
		Instant now = Instant.now();
		JobResponse job = new JobResponse(id, type, "started", message, now, now);
		jobs.put(id, job);
		activeJobId.set(id);
		return job;
	}

	public JobResponse updateJob(String jobId, String status, String message) {
		if (jobId == null) {
			return null;
		}
		JobResponse existing = jobs.get(jobId);
		if (existing == null) {
			return null;
		}
		Instant now = Instant.now();
		String msg = message != null ? message : existing.message();
		JobResponse updated = new JobResponse(existing.jobId(), existing.type(), status, msg, existing.createdAt(), now);
		jobs.put(jobId, updated);
		if ("completed".equals(status) || "failed".equals(status) || "cancelled".equals(status)) {
			activeJobId.compareAndSet(jobId, null);
		}
		return updated;
	}

	public JobResponse updateActiveJob(String status, String message) {
		String current = activeJobId.get();
		if (current != null) {
			return updateJob(current, status, message);
		}
		return null;
	}

	public JobResponse getJob(String jobId) {
		return jobs.get(jobId);
	}

	public String activeJobId() {
		return activeJobId.get();
	}
}
