package dev.aether.macro.fishing;

import dev.aether.config.AetherConfig;
import dev.aether.config.entries.StringEntry;
import dev.aether.modules.routes.Route;
import dev.aether.modules.routes.RouteStore;

import java.util.Optional;

// the display name doubles as the settings page, the routes tab and the macro menu entry
public enum FishingMacroKind {
    STRIDER("Strider Fishing", RouteStore.STRIDER_FISHING, Route.DEFAULT_WARP),
    GENERAL("Fishing Macro", RouteStore.FISHING, "");

    private final String displayName;
    private final RouteStore.Folder folder;
    private final String newRouteWarp;

    FishingMacroKind(String displayName, RouteStore.Folder folder, String newRouteWarp) {
        this.displayName = displayName;
        this.folder = folder;
        this.newRouteWarp = newRouteWarp;
    }

    public String displayName() {
        return displayName;
    }

    public RouteStore.Folder folder() {
        return folder;
    }

    public String newRouteWarp() {
        return newRouteWarp;
    }

    // looked up on use, since a constant holding the entry would load the config along with the enum
    public StringEntry routeSelection() {
        return switch (this) {
            case STRIDER -> AetherConfig.STRIDER_FISHING_RESTART_ROUTE;
            case GENERAL -> AetherConfig.FISHING_MACRO_ROUTE;
        };
    }

    AbstractFishingMacro create() {
        return switch (this) {
            case STRIDER -> new StriderFishingMacro();
            case GENERAL -> new FishingMacro();
        };
    }

    public static Optional<FishingMacroKind> forFolder(RouteStore.Folder folder) {
        for (FishingMacroKind kind : values()) {
            if (kind.folder.equals(folder)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
