package dev.advik.mcdt.exec;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The output of a {@link GameTask}.
 *
 * @param body           serialized result
 * @param tick           client tick the result was read at
 * @param queueWaitNanos time between enqueue and start of execution
 */
public record TaskResult(JsonNode body, long tick, long queueWaitNanos) {
}
