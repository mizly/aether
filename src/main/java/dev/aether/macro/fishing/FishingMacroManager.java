package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.config.ConfigHelpers;
import dev.aether.macro.MacroInput;
import dev.aether.macro.MacroStateManager;
import dev.aether.modules.failsafe.FailsafeManager;
import dev.aether.modules.routes.Route;
import dev.aether.modules.routes.RouteRunner;
import dev.aether.modules.routes.RouteStore;
import dev.aether.util.ClientUtils;
import dev.aether.util.SkyblockLocation;
import dev.aether.util.TablistUtils;
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
    private static Route warpFallback;
    private static boolean restartJumpHeld;
    private static String reportedRoute;

    private FishingMacroManager() {
    }

    // main client thread only
    public static void enable(Minecraft mc, FishingMacroKind kind) {
        disable(mc);
        ClientUtils.forceReleaseKeys();
        activeKind = kind;
        activeMacro = kind.create();
        pendingEnableTicks = ConfigHelpers.getRandomizedDelay(START_DELAY_MIN_TICKS, START_DELAY_MAX_TICKS);
        reportedRoute = null;

        Route route = selectedRoute(kind);
        activeMacro.setHome(homeOf(route));
        if (route != null && mc.player != null && !RouteRunner.isStandingAt(mc, route.end())) {
            ClientUtils.sendDebugMessage("[" + kind.displayName() + "] walking route " + route.name());
            if (beginsHere(route, SkyblockLocation.areaName(TablistUtils.findLine(mc, "Area:")))) {
                startRunner(RouteRunner.fromHere(route, mc.player.position()), route);
            } else {
                startRunner(new RouteRunner(route, false), null);
            }
        }
    }

    // null when the kind can start; the strider has no spot of its own, so it fishes where its route ends
    public static String startBlockedReason(FishingMacroKind kind) {
        return blockedStart(kind, selectedRoute(kind));
    }

    static String blockedStart(FishingMacroKind kind, Route selected) {
        return kind == FishingMacroKind.STRIDER && selected == null
                ? "Strider Fishing needs a route, pick one under Restart Route."
                : null;
    }

    // a manual start skips the warp when the player is already on its island and walks on from there
    static boolean beginsHere(Route route, String areaName) {
        return !route.hasWarp() || RouteRunner.isOnWarpIsland(route.warpCommand(), areaName);
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
        return blockedReason(activeMacro == null ? null : restartRoute(activeKind));
    }

    static String blockedReason(Route route) {
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
        if (activeMacro == null) {
            return;
        }
        Route route = selectedRoute(activeKind);
        if (route == null) {
            return;
        }
        Runnable start = () -> {
            cancelRestart();
            activeMacro.onDisable(mc);
            activeMacro.setHome(homeOf(route));
            pendingEnableTicks = 0;
            startRunner(new RouteRunner(route, hopThroughHub), null);
        };
        if (mc.isSameThread()) {
            start.run();
        } else {
            mc.execute(start);
        }
    }

    // fallbackRoute is warped and walked from the start when picking the route up where we stand fails
    private static void startRunner(RouteRunner runner, Route fallbackRoute) {
        warpFallback = fallbackRoute != null && fallbackRoute.hasWarp() ? fallbackRoute : null;
        restartJumpHeld = false;
        restartRunner = runner;
    }

    private static void cancelRestart() {
        RouteRunner runner = restartRunner;
        restartRunner = null;
        if (runner != null) {
            runner.cancel();
        }
    }

    static Route restartRoute(FishingMacroKind kind) {
        return selectedRoute(kind);
    }

    // only a route that ends somewhere can be walked, and one that cannot is pointed out once per start
    private static Route selectedRoute(FishingMacroKind kind) {
        String name = kind.routeSelection().get();
        if (name == null || name.isBlank()) {
            return null;
        }
        Route route = RouteStore.config().load(kind.folder(), name);
        if (route != null && route.end() != null) {
            return route;
        }
        if (!name.equals(reportedRoute)) {
            reportedRoute = name;
            ClientUtils.sendMessage("§e" + kind.displayName() + " cannot use route \"" + name
                    + "\" (missing or no waypoints)" + (kind == FishingMacroKind.GENERAL
                    ? ", fishing where it was started instead." : "."), false);
        }
        return null;
    }

    // home is the route's last block, even when a start right beside it skipped the walk
    private static BlockPos homeOf(Route route) {
        Route.Waypoint end = route == null ? null : route.end();
        return end == null ? null : new BlockPos(end.x(), end.y(), end.z());
    }

    // main client thread only; a macro that is still waiting to start or walking a restart is not fishing yet
    public static void onChat(String line) {
        if (restartRunner != null || pendingEnableTicks > 0) {
            return;
        }
        forwardChat(activeMacro, line);
    }

    static void forwardChat(AbstractFishingMacro macro, String line) {
        if (macro != null && line != null) {
            macro.onChat(TablistUtils.stripColors(line));
        }
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
        holdJumpInLava(mc, runner);
        if (runner.isFailed()) {
            restartRunner = null;
            Route fallback = warpFallback;
            if (fallback != null) {
                ClientUtils.sendDebugMessage("[" + activeKind.displayName() + "] could not pick the route up here ("
                        + runner.failure() + "), warping instead");
                startRunner(new RouteRunner(fallback, false), null);
                return;
            }
            String message = activeKind.displayName() + " stopped: route failed (" + runner.failure() + ").";
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

    // the walker never swims out of lava, so a leg that sinks into the pit would retry from its floor;
    // an etherwarp is left alone, since bobbing up mid aim moves the eye off its line
    private static void holdJumpInLava(Minecraft mc, RouteRunner runner) {
        boolean hold = mc.player.isInLava() && !runner.isEtherwarping();
        if (hold || restartJumpHeld) {
            MacroInput.set(mc.options.keyJump, hold);
            restartJumpHeld = hold;
        }
    }
}
