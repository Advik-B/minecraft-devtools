package dev.advik.mcdt.decompiler.isolated;

import org.jetbrains.java.decompiler.main.extern.IIdentifierRenamer;

import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Custom identifier renamer loaded strictly inside the isolated Vineflower classloader.
 * Delegates name translation to functions provided by NameService across the classloader boundary.
 */
public final class VineflowerRenamer implements IIdentifierRenamer {
	public static volatile BiFunction<String, String, String> classRenamer;
	public static volatile Function<String[], String> fieldRenamer;
	public static volatile Function<String[], String> methodRenamer;

	@Override
	public boolean toBeRenamed(Type type, String className, String element, String descriptor) {
		if (type == Type.ELEMENT_CLASS) {
			return classRenamer != null && classRenamer.apply(className, element) != null;
		} else if (type == Type.ELEMENT_FIELD) {
			return fieldRenamer != null && fieldRenamer.apply(new String[]{className, element, descriptor}) != null;
		} else if (type == Type.ELEMENT_METHOD) {
			return methodRenamer != null && methodRenamer.apply(new String[]{className, element, descriptor}) != null;
		}
		return false;
	}

	@Override
	public String getNextClassName(String fullName, String shortName) {
		if (classRenamer != null) {
			String renamed = classRenamer.apply(fullName, shortName);
			if (renamed != null) {
				return renamed;
			}
		}
		return shortName;
	}

	@Override
	public String getNextFieldName(String className, String field, String descriptor) {
		if (fieldRenamer != null) {
			String renamed = fieldRenamer.apply(new String[]{className, field, descriptor});
			if (renamed != null) {
				return renamed;
			}
		}
		return field;
	}

	@Override
	public String getNextMethodName(String className, String method, String descriptor) {
		if (methodRenamer != null) {
			String renamed = methodRenamer.apply(new String[]{className, method, descriptor});
			if (renamed != null) {
				return renamed;
			}
		}
		return method;
	}
}
