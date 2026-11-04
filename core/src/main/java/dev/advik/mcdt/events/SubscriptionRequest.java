package dev.advik.mcdt.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class SubscriptionRequest {
	public String action; // "subscribe" or "unsubscribe", defaults to "subscribe"
	public List<String> topics;
	public Map<String, TopicFilter> filters;

	@JsonIgnoreProperties(ignoreUnknown = true)
	public static final class TopicFilter {
		public boolean fullPayload = false;
		public List<String> types;
	}
}
