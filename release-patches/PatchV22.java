import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class PatchV22 {
    private static final String HUNTING =
            "dev/aether/modules/pest/helpers/PestHuntingController";
    private static final String FOLLOW_DESCRIPTOR =
            "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/entity/Entity;Z)V";
    private static final double RELIABLE_RANGE = 4.75d;

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path previousClass = Path.of(args[1]);
        patchHunting(root.resolve(HUNTING + ".class"), previousClass);
    }

    private static void patchHunting(Path path, Path previousClass) throws Exception {
        ClassNode node = read(path);
        ClassNode previous = read(previousClass);

        MethodNode restoredFollow = findMethod(previous, "maintainFollowDistance", FOLLOW_DESCRIPTOR);
        tuneFollowMethod(restoredFollow);

        for (int i = 0; i < node.methods.size(); i++) {
            MethodNode method = node.methods.get(i);
            if (method.name.equals("maintainFollowDistance")
                    && method.desc.equals(FOLLOW_DESCRIPTOR)) {
                node.methods.set(i, restoredFollow);
            } else if (method.name.equals("handleStun")) {
                addRangeGuard(method, 2);
            } else if (method.name.equals("handleThrow")) {
                addRangeGuard(method, 3);
            } else if (method.name.equals("handleReel")) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc
                            && ldc.cst instanceof Long value
                            && value == 1500L) {
                        ldc.cst = 900L;
                    }
                }
            }
        }
        write(path, node);
    }

    private static void tuneFollowMethod(MethodNode method) {
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof FieldInsnNode field
                    && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals("dev/aether/config/AetherConfig")
                    && field.name.equals("PEST_HUNTING_FOLLOW_DISTANCE")) {
                AbstractInsnNode end = instruction;
                while (end != null && end.getOpcode() != Opcodes.F2D) {
                    end = end.getNext();
                }
                if (end == null) {
                    throw new IllegalStateException("Could not find follow-distance conversion");
                }
                method.instructions.insertBefore(instruction, new LdcInsnNode(4.0d));
                AbstractInsnNode cursor = instruction;
                while (true) {
                    AbstractInsnNode next = cursor.getNext();
                    method.instructions.remove(cursor);
                    if (cursor == end) {
                        break;
                    }
                    cursor = next;
                }
                break;
            }
        }

        // The old boolean suppressed vertical correction throughout STUN and
        // SWAP. Always align while unattached; attached catches use a different
        // hold-position path and never call this method.
        for (AbstractInsnNode instruction = method.instructions.getFirst();
                instruction != null;
                instruction = instruction.getNext()) {
            if (instruction instanceof VarInsnNode variable
                    && variable.getOpcode() == Opcodes.ILOAD
                    && variable.var == 2) {
                method.instructions.set(instruction, new InsnNode(Opcodes.ICONST_1));
            }
        }
    }

    private static void addRangeGuard(MethodNode method, int targetLocal) {
        LabelNode inRange = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new VarInsnNode(Opcodes.ALOAD, 0));
        guard.add(new VarInsnNode(Opcodes.ALOAD, targetLocal));
        guard.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                HUNTING,
                "horizontalDistanceTo",
                "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/entity/Entity;)D",
                false));
        guard.add(new LdcInsnNode(RELIABLE_RANGE));
        guard.add(new InsnNode(Opcodes.DCMPL));
        guard.add(new JumpInsnNode(Opcodes.IFLE, inRange));
        guard.add(new InsnNode(Opcodes.RETURN));
        guard.add(inRange);
        guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        method.instructions.insert(guard);
    }

    private static MethodNode findMethod(ClassNode node, String name, String descriptor) {
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(descriptor)) {
                return method;
            }
        }
        throw new IllegalStateException("Missing method " + name + descriptor);
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
}
