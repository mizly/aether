import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class PatchV21 {
    private static final String HUNTING =
            "dev/aether/modules/pest/helpers/PestHuntingController";
    private static final String TARGETS =
            "dev/aether/modules/pest/helpers/PestTargetController";
    private static final String NAVIGATION =
            "dev/aether/modules/pest/helpers/PestNavigationCoordinator";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        patchHunting(root.resolve(HUNTING + ".class"));
        patchTargets(root.resolve(TARGETS + ".class"));
        patchNavigation(root.resolve(NAVIGATION + ".class"));
    }

    private static void patchHunting(Path path) throws Exception {
        ClassNode node = read(path);
        for (MethodNode method : node.methods) {
            if (method.name.equals("clearHunt")) {
                method.instructions.insert(new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "dev/aether/modules/rotation/RotationManager",
                        "cancelRotation",
                        "()V",
                        false));
            } else if (method.name.equals("isPullInProgress")) {
                replaceBody(method, new InsnNode(Opcodes.ICONST_0), new InsnNode(Opcodes.IRETURN));
            } else if (method.name.equals("maintainFollowDistance")) {
                replaceBody(
                        method,
                        new VarInsnNode(Opcodes.ALOAD, 0),
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                HUNTING,
                                "holdLassoPosition",
                                "(Lnet/minecraft/client/Minecraft;)V",
                                false),
                        new InsnNode(Opcodes.RETURN));
            } else if (method.name.equals("maintainAim")) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc
                            && ldc.cst instanceof Double value
                            && value == 3.0d) {
                        ldc.cst = 1.5d;
                    }
                }
            }
        }
        write(path, node);
    }

    private static void patchTargets(Path path) throws Exception {
        ClassNode node = read(path);
        for (MethodNode method : node.methods) {
            if (method.name.equals("rotateToTarget")) {
                replaceBody(method, new InsnNode(Opcodes.RETURN));
            }
        }
        write(path, node);
    }

    private static void patchNavigation(Path path) throws Exception {
        ClassNode node = read(path);
        for (FieldNode field : node.fields) {
            if (field.name.equals("HINT_PITCH_WAIT_TIMEOUT_MS")) {
                field.value = 750L;
            }
        }
        for (MethodNode method : node.methods) {
            if (!method.name.equals("handleGetLocation")) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode ldc
                        && ldc.cst instanceof Long value
                        && value == 3000L) {
                    ldc.cst = 750L;
                } else if (instruction instanceof IntInsnNode integer
                        && integer.operand == 3000) {
                    integer.operand = 750;
                }
            }
        }
        write(path, node);
    }

    private static ClassNode read(Path path) throws Exception {
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(path)).accept(node, 0);
        return node;
    }

    private static void write(Path path, ClassNode node) throws Exception {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Files.write(path, writer.toByteArray());
    }

    private static void replaceBody(MethodNode method, AbstractInsnNode... instructions) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        InsnList replacement = new InsnList();
        for (AbstractInsnNode instruction : instructions) {
            replacement.add(instruction);
        }
        method.instructions.add(replacement);
    }
}
