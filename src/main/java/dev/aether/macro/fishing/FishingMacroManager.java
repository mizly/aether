package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.routes.Route;
import dev.aether.modules.routes.RouteRunner;
import dev.aether.modules.routes.RouteStore;
import dev.aether.util.ClientUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

// tick() has to be wired to END_CLIENT_TICK
public final class FishingMacroManager {

    private static final int START_DELAY_MIN_TICKS = 1;
    private static final int START_DELAY_MAX_TICKS = 10;

    private static AbstractFishingMacro activeMacro;
    private static FishingMacroKind activeKind;
    private static int pendingEnableTicks;
    private static volatile RouteRunner restartRunner;

    private FishingMacroManager() {
    }

    // main client thread only
    public static void enable(Minecraft mc, FishingMacroKind kind) {
        disable(mc);
        ClientUtils.forceReleaseKeys();
        activeKind = kind;
        activeMacro = kind.create();
        pendingEnableTicks = ConfigHelpers.getRandomizedDelay(START_DELAY_MIN_TICKS, START_DELAY_MAX_TICKS);

        // the route ends on the fishing spot, so standing there already skips the warp and the walk
        Route route = restartRoute(kind);
        activeMacro.setHome(homeOf(route));
        if (route != null && mc.player != null && !RouteRunner.isStandingAt(mc, route.end())) {
            ClientUtils.sendDebugMessage("[" + kind.displayName() + "] walking route " + route.name());
            restartRunner = new RouteRunner(route, false);
        }
    }

    // main client thread only
    public static void disable(Minecraft mc) {
        cancelRestart();
        if (activeMacro != null) {
            activeMacro.onDisable(mc);
            activeMacro = null;
            pendingEnableTicks = 0;
        }
        activeKind = null;
    }

    public static boolean isActive() {
        return activeMacro != null;
    }

    public static boolean isRestarting() {
        return restartRunner != null;
    }

    public static FishingMacroKind activeKind() {
        return activeKind;
    }

    public static boolean canRestartInNewLobby() {
        return restartBlockedReason() == null;
    }

    // null when a restart can run; it starts from the hub or a fresh lobby, so only the route's warp gets back
    public static String restartBlockedReason() {
        Route route = activeMacro == null ? null : restartRoute(activeKind);
        if (route == null) {
            return "No restart route selected, macro stopped.";
        }
        return route.hasWarp() ? null : "Restart route has no warp, macro stopped.";
    }

    public static void restartInNewLobby(Minecraft mc) {
        beginRestart(mc, true);
    }

    // leaving the island already put us in a new lobby, so only the warp back and the route are needed
    public static boolean restartAfterLeavingIsland(Minecraft mc) {
        if (!canRestartInNewLobby()) {
            return false;
        }
        beginRestart(mc, false);
        return true;
    }

    private static void beginRestart(Minecraft mc, boolean hopThroughHub) {
        Route route = activeMacro == null ? null : restartRoute(activeKind);
        if (route == null) {
            return;
        }
        Runnable start = () -> {
            cancelRestart();
            activeMacro.onDisable(mc);
            activeMacro.setHome(homeOf(route));
            pendingEnableTicks = 0;
            restartRunner = new RouteRunner(route, hopThroughHub);
        };
        if (mc.isSameThread()) {
            start.run();
        } else {
            mc.execute(start);
        }
    }

    private static void cancelRestart() {
        RouteRunner runner = restartRunner;
        restartRunner = null;
        if (runner != null) {
            runner.cancel();
        }
    }

    // only a route that ends somewhere can be walked
    static Route restartRoute(FishingMacroKind kind) {
        String name = kind.routeSelection().get();
        if (name == null || name.isBlank()) {
            return null;
        }
        Route route = RouteStore.config().load(kind.folder(), name);
        return route == null || route.end() == null ? null : route;
    }

    // home is the route's last block, even when a start right beside it skipped the walk
    private static BlockPos homeOf(Route route) {
        Route.Waypoint end = route == null ? null : route.end();
        return end == null ? null : new BlockPos(end.x(), end.y(), end.z());
    }

    // releases the macro's keys without disabling it
    public static void releaseInputs(Minecraft mc) {
        if (activeMacro != null && mc != null && mc.options != null) {
            activeMacro.releaseAll(mc);
        }
    }

    public static void tick(Minecraft mc) {
        if (activeMacro == null || mc.player == null) {
            return;
        }

        RouteRunner runner = restartRunner;
        if (runner != null) {
            tickRestart(mc, runner);
            return;
        }

        if (pendingEnableTicks > 0) {
            if (--pendingEnableTicks > 0) {
                return;
            }
            activeMacro.onEnable(mc);
            return;
        }

        activeMacro.onTick(mc);
    }

    private static void tickRestart(Minecraft mc, RouteRunner runner) {
        runner.tick(mc);
        if (runner.isFailed()) {
            restartRunner = null;
            String message = activeKind.displayName() + " stopped: restart route failed (" + runner.failure() + ").";
            ClientUtils.sendMessage("§c" + message, false);
            MacroStateManager.stopMacro(mc, message, false);
            return;
        }
        if (!runner.isDone()) {
            return;
        }
        restartRunner = null;
        // the warps and legs turned the camera and swapped slots on purpose, so the failsafes start fresh
        FailsafeManager.resetRuntimeState();
        FailsafeManager.syncSelectedSlotFromClient(mc);
        FailsafeManager.syncExpectedRotationFromClient(mc);
        FailsafeManager.addRotationGracePeriod(AetherConfig.FAILSAFE_ROTATION_WARP_GRACE_MS.get());
        pendingEnableTicks = 0;
        activeMacro.onEnable(mc);
    }
}
