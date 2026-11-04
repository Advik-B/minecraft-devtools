package dev.advik.mcdt.json;

import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** The single Jackson configuration used for API responses, config, and discovery files. */
public final class Json {
	public static final ObjectMapper MAPPER = create();

	private Json() {
	}

	private static ObjectMapper create() {
		JsonMapper.Builder builder = JsonMapper.builder()
				.addModule(new JavaTimeModule())
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
		if (RecordCompatIntrospector.needed()) {
			builder.annotationIntrospector(AnnotationIntrospector.pair(
					new RecordCompatIntrospector(), new JacksonAnnotationIntrospector()));
		}
		return builder.build();
	}
}
