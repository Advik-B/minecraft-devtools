package dev.advik.mcdt.decompiler;

import dev.advik.mcdt.reflection.NameService;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Renames runtime names (intermediary, SRG) in class files to Mojang names before decompiling, so the decompiler
 * sees consistent bytecode. Vineflower's own identifier renaming breaks lambda lookups, so it is not used.
 * ASM comes from the mod loader at runtime (every loader ships it for Mixin) and is never bundled.
 */
final class MojangRemapper extends Remapper {
	private final NameService names;

	MojangRemapper(NameService names) {
		this.names = names;
	}

	/** The class file with every runtime name it mentions replaced by the Mojang name. */
	byte[] remap(byte[] classFile) {
		ClassReader reader = new ClassReader(classFile);
		ClassWriter writer = new ClassWriter(0);
		reader.accept(new ClassRemapper(writer, this), 0);
		return writer.toByteArray();
	}

	@Override
	public String map(String internalName) {
		String dotted = internalName.replace('/', '.');
		String mapped = names.resolveClassName(dotted);
		return mapped == null || mapped.equals(dotted) ? internalName : mapped.replace('.', '/');
	}

	@Override
	public String mapMethodName(String owner, String name, String descriptor) {
		if (name.startsWith("<")) {
			return name;
		}
		String mapped = names.resolveMethodByName(owner.replace('/', '.'), name);
		return !mapped.equals(name) ? mapped : names.resolveMethodNameAnyOwner(name);
	}

	@Override
	public String mapInvokeDynamicMethodName(String name, String descriptor) {
		return names.resolveMethodNameAnyOwner(name);
	}

	@Override
	public String mapFieldName(String owner, String name, String descriptor) {
		String mapped = names.resolveFieldByName(owner.replace('/', '.'), name);
		return !mapped.equals(name) ? mapped : names.resolveFieldNameAnyOwner(name);
	}

	@Override
	public String mapRecordComponentName(String owner, String name, String descriptor) {
		return mapFieldName(owner, name, descriptor);
	}
}
