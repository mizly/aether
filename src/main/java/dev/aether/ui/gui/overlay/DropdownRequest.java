package dev.aether.ui.gui.overlay;

import dev.aether.ui.gui.Icon;
import dev.aether.ui.gui.Rect;

import java.util.List;
import java.util.function.IntConsumer;

// a menu of options anchored under a field. selected is -1 when the stored value matches no option.
// onPick runs once, with the chosen index, when the user picks; closing without a pick changes nothing
public record DropdownRequest(Object anchorId, Rect anchor, String title, List<Option> options, int selected,
                              IntConsumer onPick) {
    public DropdownRequest {
        options = List.copyOf(options);
    }

    public static DropdownRequest of(Object anchorId, Rect anchor, String title, List<Option> options, int selected,
                                     IntConsumer onPick) {
        return new DropdownRequest(anchorId, anchor, title, options, selected, onPick);
    }

    public record Option(String label, Icon icon, boolean enabled) {
        public static Option of(String label, Icon icon) {
            return new Option(label, icon, true);
        }
    }
}
