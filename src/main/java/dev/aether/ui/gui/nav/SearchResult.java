package dev.aether.ui.gui.nav;

import dev.aether.ui.gui.Icon;

import java.util.List;

// one row of the command palette or a search field. path is the localised breadcrumb (category, page,
// group); key is the stable id of what it points at (page id, GroupKey, SettingKey, action id); alsoOn
// names the pages a collapsed mirror of this knob also sits on
public record SearchResult(Kind kind, String title, List<String> path, Icon icon, Runnable action, Object key,
                           Match match, List<String> alsoOn) {

    // in ranking order within one match class
    public enum Kind { PAGE, ACTION, GROUP, SECTION, SETTING }

    // best first; DESCRIPTION is a hit only in the explicit description text
    public enum Match { EXACT, PREFIX, WORD_PREFIX, CONTAINS, DESCRIPTION, FUZZY }

    public SearchResult {
        path = List.copyOf(path);
        alsoOn = List.copyOf(alsoOn);
    }

    public void run() {
        if (action != null) {
            action.run();
        }
    }
}
