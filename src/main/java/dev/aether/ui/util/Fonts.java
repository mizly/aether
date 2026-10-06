package dev.aether.ui.util;

import java.util.List;

// names of the fonts NanoVGManager loads from the mod's resources
public final class Fonts {
    
    public static final String REGULAR = "Inter-Regular";
    
    public static final String BOLD = "Inter-Bold";
    
    public static final String MONO = "Inter-Mono";

    public static final String SCOREBOARD_BOLD = "Scoreboard-Bold";

    public static final String UI_REGULAR = "ui-regular";

    public static final String UI_MEDIUM = "ui-medium";

    public static final String UI_SEMIBOLD = "ui-semibold";

    public static final String UI_BOLD = "ui-bold";

    public static final String UI_MONO = "ui-mono";

    public static final String UI_MONO_BOLD = "ui-mono-bold";

    public static final List<String> UI_ALL = List.of(UI_REGULAR, UI_MEDIUM, UI_SEMIBOLD, UI_BOLD, UI_MONO, UI_MONO_BOLD);
    
    private Fonts() {}
}
