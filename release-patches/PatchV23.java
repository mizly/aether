import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class PatchV23 {
    private static final String HUNTING =
            "dev/aether/modules/pest/helpers/PestHuntingController";
    private static final String COMBAT =
            "dev/aether/modules/pest/helpers/PestCombatCoordinator";
    private static final String ROTATION =
            "dev/aether/modules/rotation/RotationManager";
    private static final String INIT_DESC =
            "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/phys/Vec3;JF)V";
    private static final String FORCE_DESC =
            "(Lnet/minecraft/client/Minecraft;Lnet/minecraft/world/phys/Vec3;J)V";

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        patchHunting(root.resolve(HUNTING + ".class"));
        patchCombat(root.resolve(COMBAT + ".class"));
    }

    private static void patchHunting(Path path) throws Exception {
        ClassNode node = read(path);
        for (MethodNode method : node.methods) {
            if (!method.name.equals("handleStun") && !method.name.equals("handleThrow")) {
                continue;
            }
            // v22's tight 4.75-block entry guard caused the visible wait before
            // stunning. Keep the approach movement, but make this guard merely
            // a corruption/outlier safeguard so the sequence begins at handoff.
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof LdcInsnNode ldc
                        && ldc.cst instanceof Double value
                        && value == 4.75d) {
                    ldc.cst = 64.0d;
                    break;
                }
            }
        }
        write(path, node);
    }

    private static void patchCombat(Path path) throws Exception {
        ClassNode node = read(path);
        boolean flyPatched = false;
        boolean approachPatched = false;
        for (MethodNode method : node.methods) {
            if (method.name.equals("handleFlyToPest")) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(ROTATION)
                            && call.name.equals("initiatePestRotation")
                            && call.desc.equals(INIT_DESC)) {
                        call.owner = COMBAT;
                        call.name = "keepFlyPathHeading";
                        flyPatched = true;
                    }
                }
            } else if (method.name.equals("handleApproachPest")) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(ROTATION)
                            && call.name.equals("smoothForceRotation")
                            && call.desc.equals(FORCE_DESC)) {
                        call.owner = COMBAT;
                        call.name = "keepApproachPathHeading";
                        approachPatched = true;
                    }
                }
            }
        }
        if (!flyPatched || !approachPatched) {
            throw new IllegalStateException(
                    "Expected navigation rotation sites were not found: fly="
                            + flyPatched + ", approach=" + approachPatched);
        }
        node.methods.add(noOp("keepFlyPathHeading", INIT_DESC));
        node.methods.add(noOp("keepApproachPathHeading", FORCE_DESC));
        write(path, node);
    }

    private static MethodNode noOp(String name, String descriptor) {
        MethodNode method = new MethodNode(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name,
                descriptor,
                null,
                null);
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        return method;
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
