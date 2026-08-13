import java.nio.file.Files;
import java.nio.file.Path;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class PatchV24 {
    private static final String HUNTING =
            "dev/aether/modules/pest/helpers/PestHuntingController";
    private static final long DISABLED_RETRY_MS = 86_400_000L;

    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]).resolve(HUNTING + ".class");
        ClassNode node = read(path);
        boolean samePromptRetryDisabled = false;
        boolean missingPromptWatchdogDisabled = false;

        for (MethodNode method : node.methods) {
            if (method.name.equals("handleHuntPest")) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc
                            && ldc.cst instanceof Long value
                            && value == 900L) {
                        ldc.cst = DISABLED_RETRY_MS;
                        samePromptRetryDisabled = true;
                    }
                }
            } else if (method.name.equals("handleReel")) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc
                            && ldc.cst instanceof Long value
                            && (value == 6000L || value == 3000L)) {
                        ldc.cst = DISABLED_RETRY_MS;
                        if (value == 6000L) {
                            missingPromptWatchdogDisabled = true;
                        }
                    }
                }
            }
        }

        if (!samePromptRetryDisabled || !missingPromptWatchdogDisabled) {
            throw new IllegalStateException(
                    "Expected reel safeguards were not found: samePrompt="
                            + samePromptRetryDisabled + ", watchdog="
                            + missingPromptWatchdogDisabled);
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
}
