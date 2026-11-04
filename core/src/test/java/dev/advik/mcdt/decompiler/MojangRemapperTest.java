package dev.advik.mcdt.decompiler;

import dev.advik.mcdt.reflection.NameService;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MojangRemapperTest {
	private static final String MAPPINGS = """
			net.minecraft.client.Minecraft -> net.minecraft.class_310:
			    int fps -> field_1738
			    void tick() -> method_1574
			net.minecraft.client.player.LocalPlayer -> net.minecraft.class_746:
			""";

	@Test
	void remapsClassesMembersAndReferencesThroughSubclasses() throws Exception {
		NameService names = new NameService(NameService.RuntimeNames.IDENTITY);
		names.loadFromReader(new BufferedReader(new StringReader(MAPPINGS)));

		byte[] remapped = new MojangRemapper(names).remap(intermediaryClass());

		List<String> seen = new ArrayList<>();
		new ClassReader(remapped).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				seen.add("class " + name + " extends " + superName);
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				seen.add("field " + name + " " + descriptor);
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				seen.add("method " + name + descriptor);
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String insnName, String insnDescriptor, boolean itf) {
						seen.add("  call " + owner + "." + insnName + insnDescriptor);
					}

					@Override
					public void visitFieldInsn(int opcode, String owner, String insnName, String insnDescriptor) {
						seen.add("  get " + owner + "." + insnName + " " + insnDescriptor);
					}
				};
			}
		}, 0);

		assertEquals(List.of(
				"class net/minecraft/client/Minecraft extends java/lang/Object",
				"field fps I",
				"method tick()V",
				"method withPlayer(Lnet/minecraft/client/player/LocalPlayer;)V",
				// Owner is the subclass, which does not declare these members: resolved without the owner.
				"  call net/minecraft/client/player/LocalPlayer.tick()V",
				"  get net/minecraft/client/player/LocalPlayer.fps I"
		), seen);
	}

	/** {@code class class_310 { int field_1738; void method_1574() {} void method_9(class_746 p) { p.method_1574(); p.field_1738; } }}. */
	private static byte[] intermediaryClass() {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "net/minecraft/class_310", null, "java/lang/Object", null);
		cw.visitField(Opcodes.ACC_PUBLIC, "field_1738", "I", null, null).visitEnd();
		MethodVisitor tick = cw.visitMethod(Opcodes.ACC_PUBLIC, "method_1574", "()V", null, null);
		tick.visitCode();
		tick.visitInsn(Opcodes.RETURN);
		tick.visitMaxs(0, 0);
		tick.visitEnd();
		// Unmapped method name, so it stays; its body reaches inherited members through the subclass.
		MethodVisitor with = cw.visitMethod(Opcodes.ACC_PUBLIC, "withPlayer", "(Lnet/minecraft/class_746;)V", null, null);
		with.visitCode();
		with.visitVarInsn(Opcodes.ALOAD, 1);
		with.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_746", "method_1574", "()V", false);
		with.visitVarInsn(Opcodes.ALOAD, 1);
		with.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_746", "field_1738", "I");
		with.visitInsn(Opcodes.POP);
		with.visitInsn(Opcodes.RETURN);
		with.visitMaxs(0, 0);
		with.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
