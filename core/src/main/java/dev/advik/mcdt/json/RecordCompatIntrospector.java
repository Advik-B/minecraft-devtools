package dev.advik.mcdt.json;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.cfg.MapperConfig;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.AnnotatedClass;
import com.fasterxml.jackson.databind.introspect.AnnotatedConstructor;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.AnnotatedParameter;
import com.fasterxml.jackson.databind.introspect.NopAnnotationIntrospector;
import com.fasterxml.jackson.databind.introspect.VisibilityChecker;

import java.lang.reflect.RecordComponent;

/**
 * Gives records their usual JSON shape on Java 8-15, where MCDT runs as bytecode downgraded by JvmDowngrader.
 * Jackson recognises records through {@code java.lang.Record}, which those runtimes lack, so a downgraded record
 * looks like an empty bean. This applies the same rules: properties come from the component fields in order, and
 * the canonical constructor is the properties-based creator. Calls to {@link Class#isRecord()} and
 * {@link Class#getRecordComponents()} are rewritten by JvmDowngrader to read the components it records.
 * Not registered on Java 16+, where Jackson handles records itself.
 */
final class RecordCompatIntrospector extends NopAnnotationIntrospector {
	private static final long serialVersionUID = 1L;

	/** True when the runtime has no {@code java.lang.Record}, so Jackson cannot see records by itself. */
	static boolean needed() {
		try {
			Class.forName("java.lang.Record");
			return false;
		} catch (ClassNotFoundException e) {
			return true;
		}
	}

	@Override
	public VisibilityChecker<?> findAutoDetectVisibility(AnnotatedClass ac, VisibilityChecker<?> checker) {
		return ac.getRawType().isRecord() ? checker.withFieldVisibility(JsonAutoDetect.Visibility.ANY) : checker;
	}

	@Override
	public JsonCreator.Mode findCreatorAnnotation(MapperConfig<?> config, Annotated a) {
		return a instanceof AnnotatedConstructor ctor && isCanonical(ctor) ? JsonCreator.Mode.PROPERTIES : null;
	}

	@Override
	public String findImplicitPropertyName(AnnotatedMember member) {
		if (member instanceof AnnotatedParameter parameter
				&& parameter.getOwner() instanceof AnnotatedConstructor ctor
				&& isCanonical(ctor)) {
			return ctor.getDeclaringClass().getRecordComponents()[parameter.getIndex()].getName();
		}
		return null;
	}

	private static boolean isCanonical(AnnotatedConstructor ctor) {
		Class<?> type = ctor.getDeclaringClass();
		if (!type.isRecord()) {
			return false;
		}
		RecordComponent[] components = type.getRecordComponents();
		if (components.length != ctor.getParameterCount()) {
			return false;
		}
		for (int i = 0; i < components.length; i++) {
			if (components[i].getType() != ctor.getRawParameterType(i)) {
				return false;
			}
		}
		return true;
	}
}
