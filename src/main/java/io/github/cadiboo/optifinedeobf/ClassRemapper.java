package io.github.cadiboo.optifinedeobf;

import io.github.cadiboo.optifinedeobf.mapping.MappingService;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PROTECTED;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ASM5;
import static org.objectweb.asm.Opcodes.H_INVOKEVIRTUAL;

/**
 * @author Cadiboo
 */
public class ClassRemapper {

	public final MappingService mappingService;
	public final boolean makePublic;
	public final boolean definalise;

	ClassRemapper(final MappingService mappingService, final boolean makePublic, final boolean definalise) {
		this.mappingService = mappingService;
		this.makePublic = makePublic;
		this.definalise = definalise;
	}

	/**
	 * Returns null if there's an error
	 */
	byte[] remapClass(byte[] inputClass) {
		try {
			final ClassNode classNode = new ClassNode(ASM5);
			final ClassReader classReader = new ClassReader(inputClass);
			classReader.accept(classNode, 0);

			classNode.access = correctAccess(classNode.access);
			classNode.name = mappingService.mapClass(classNode.name);
			for (InnerClassNode innerClassNode : classNode.innerClasses) {
				innerClassNode.access = correctAccess(innerClassNode.access);
				innerClassNode.name = mappingService.mapClass(innerClassNode.name);
			}

			for (final FieldNode field : classNode.fields) {
				field.access = correctAccess(field.access);
				field.name = mappingService.mapField(classNode.name, field.name);
			}

			for (final MethodNode method : classNode.methods) {
				if ((makePublic || definalise) && !method.name.equals("<clinit>"))
					method.access = correctAccess(method.access);
				method.name = mapLamdaMethod(classNode.name, method.name, method.desc);
				method.instructions.iterator().forEachRemaining(insn -> {
					if (insn instanceof FieldInsnNode) {
						final FieldInsnNode fieldInsn = (FieldInsnNode) insn;
						fieldInsn.name = mappingService.mapField(classNode.name, fieldInsn.name);
					} else if (insn instanceof MethodInsnNode) {
						final MethodInsnNode methodInsn = (MethodInsnNode) insn;
						methodInsn.name = mapLamdaMethod(classNode.name, methodInsn.name, methodInsn.desc);
					} else if (insn instanceof InvokeDynamicInsnNode) {
						final Object[] bsmArgs = ((InvokeDynamicInsnNode) insn).bsmArgs;
						for (int i = 0; i < bsmArgs.length; ++i) {
							final Object bsmArg = bsmArgs[i];
							if (bsmArg instanceof Handle) {
								Handle handle = (Handle) bsmArg;
								String mappedName = mapHandleName(handle);
								if (!mappedName.equals(handle.getName()))
									bsmArgs[i] = new Handle(handle.getTag(), handle.getOwner(), mappedName, handle.getDesc(), handle.isInterface());
							}
						}
					}
				});
			}

			final ClassWriter classWriter = new ClassWriter(ASM5);
			classNode.accept(classWriter);
			return classWriter.toByteArray();
		} catch (Exception e) {
			return null;
		}
	}

	int correctAccess(int access) {
		if (makePublic) {
			// Remove all access (mask it away)
			access &= ~(ACC_PUBLIC | ACC_PROTECTED | ACC_PRIVATE);
			// Add public access
			access |= ACC_PUBLIC;
		}
		if (definalise) {
			// Mask off final
			access &= ~ACC_FINAL;
		}
		return access;
	}

	String mapHandleName(final Handle handle) {
		int tag = handle.getTag();
//		Fields: H_GETFIELD H_GETSTATIC H_PUTFIELD H_PUTSTATIC
//		Methods: H_INVOKEVIRTUAL H_INVOKESTATIC H_INVOKESPECIAL H_NEWINVOKESPECIAL H_INVOKEINTERFACE
		if (tag < H_INVOKEVIRTUAL) // Field
			return mappingService.mapField(handle.getOwner(), handle.getName());
		else {
			return mapLamdaMethod(handle.getOwner(), handle.getName(), handle.getDesc());
		}
	}

	/**
	 * Remaps lambda method names like `lambda$func_1111$0` to `lambda$mappedName$0`
	 */
	String mapLamdaMethod(final String owner, final String name, final String desc) {
		if (name.startsWith("lambda$")) { // Handle nested lamdbas
			int start$ = name.indexOf('$') + 1;
			int last$ = name.lastIndexOf('$');
			return name.substring(0, start$) + mappingService.mapMethod(owner, name.substring(start$, last$), desc) + name.substring(last$);
		} else
			return mappingService.mapMethod(owner, name, desc);
	}

}
