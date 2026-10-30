package dev.aether.modules.failsafe;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TeleportPacketHookTest {
    @Test
    void minecraftHasTheRequiredMainThreadPositionHookAndCommandMethod() throws Exception {
        AtomicBoolean positionHook = new AtomicBoolean();
        AtomicBoolean commandHook = new AtomicBoolean();
        try (InputStream stream = getClass().getResourceAsStream(
                "/net/minecraft/client/multiplayer/ClientPacketListener.class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if (name.equals("sendCommand") && descriptor.equals("(Ljava/lang/String;)V")) {
                        commandHook.set(true);
                    }
                    if (!name.equals("handleMovePlayer") || !descriptor.equals(
                            "(Lnet/minecraft/network/protocol/game/ClientboundPlayerPositionPacket;)V")) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                boolean isInterface) {
                            if (owner.equals("net/minecraft/network/protocol/PacketUtils")
                                    && name.equals("ensureRunningOnSameThread")
                                    && descriptor.equals("(Lnet/minecraft/network/protocol/Packet;"
                                    + "Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V")) {
                                positionHook.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(positionHook.get(), "Position packet injection target must match this Minecraft version");
        assertTrue(commandHook.get(), "Teleport command injection target must match this Minecraft version");
    }
}
