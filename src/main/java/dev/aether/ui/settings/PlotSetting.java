package dev.aether.ui.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

// garden plots picked on the Configure Plots map. stored values are read as tokens: known plots are written
// back as bare digits or "barn", anything unrecognised is kept until the user removes it, and nothing is
// rewritten until an edit
public final class PlotSetting extends AbstractSetting<PlotSetting> {
    public enum Mode { SINGLE, MULTI, ORDERED }

    // what an empty selection means to the feature, so the picker can say it instead of showing nothing
    public enum EmptyMeaning { NONE, ALL, STAY, CURRENT }

    private final Mode mode;
    private final Supplier<List<String>> getter;
    private final Consumer<List<String>> setter;
    private boolean allowBarn;
    private EmptyMeaning emptyMeaning = EmptyMeaning.NONE;
    private Predicate<PlotToken> restriction = token -> true;
    private Setting legacyView;

    public PlotSetting(String name, Mode mode, Supplier<List<String>> getter, Consumer<List<String>> setter) {
        super(name);
        this.mode = mode;
        this.getter = getter;
        this.setter = setter;
    }

    // one plot kept in a string, like the teleport targets; emptyValue is what both reads as and writes for
    // no plot, "0" for traps and junk until their consumers skip unusable plots
    public static PlotSetting single(String name, Supplier<String> getter, Consumer<String> setter, String emptyValue) {
        return new PlotSetting(name, Mode.SINGLE,
                () -> {
                    String value = getter.get();
                    return value == null || value.isBlank() || value.trim().equals(emptyValue) ? List.of() : List.of(value);
                },
                values -> setter.accept(values.isEmpty() ? emptyValue : values.getFirst()));
    }

    // the old menu shows a plot setting as the text or list field it replaces
    public static Setting asLegacy(Setting setting) {
        return setting instanceof PlotSetting plot ? plot.legacyView() : setting;
    }

    public PlotSetting allowBarn() {
        allowBarn = true;
        return this;
    }

    public PlotSetting emptyMeaning(EmptyMeaning meaning) {
        emptyMeaning = meaning;
        return this;
    }

    // e.g. greenhouse plots only; checked together with the barn rule by isSelectable
    public PlotSetting restrictTo(Predicate<PlotToken> restriction) {
        this.restriction = restriction;
        return this;
    }

    public Mode mode() {
        return mode;
    }

    public boolean allowsBarn() {
        return allowBarn;
    }

    public EmptyMeaning emptyMeaning() {
        return emptyMeaning;
    }

    // stored order, duplicates collapsed, unrecognised entries included; a barn where none is allowed counts as unrecognised
    public List<PlotToken> tokens() {
        Set<PlotToken> tokens = new LinkedHashSet<>();
        for (String raw : getter.get()) {
            PlotToken token = PlotToken.parse(raw);
            if (token != null) {
                tokens.add(token.isBarn() && !allowBarn ? PlotToken.unknown(raw) : token);
            }
        }
        return List.copyOf(tokens);
    }

    public List<PlotToken> selection() {
        return tokens().stream().filter(PlotToken::isKnown).toList();
    }

    public boolean isEmpty() {
        return tokens().isEmpty();
    }

    public boolean isSelected(PlotToken token) {
        return token.isKnown() && tokens().contains(token);
    }

    // 1-based position among the selected plots, the visit order for ORDERED; 0 when not selected
    public int order(PlotToken token) {
        return token.isKnown() ? selection().indexOf(token) + 1 : 0;
    }

    public boolean isSelectable(PlotToken token) {
        return token.isKnown() && (!token.isBarn() || allowBarn) && restriction.test(token);
    }

    // a single setting picks the plot; multi and ordered add it at the end or take it out, renumbering the rest
    public void toggle(PlotToken token) {
        if (!isSelectable(token)) {
            return;
        }
        if (mode == Mode.SINGLE) {
            write(List.of(token));
            return;
        }
        List<PlotToken> tokens = new ArrayList<>(tokens());
        if (!tokens.remove(token)) {
            tokens.add(token);
        }
        write(tokens);
    }

    public void select(PlotToken token) {
        if (!isSelected(token)) {
            toggle(token);
        }
    }

    // also how an unrecognised entry is dropped
    public void remove(PlotToken token) {
        List<PlotToken> tokens = new ArrayList<>(tokens());
        if (tokens.remove(token)) {
            write(tokens);
        }
    }

    public void clear() {
        write(List.of());
    }

    @Override
    public SettingType getType() {
        return SettingType.PLOT;
    }

    private void write(List<PlotToken> tokens) {
        setter.accept(tokens.stream().map(PlotToken::text).toList());
    }

    private Setting legacyView() {
        if (legacyView == null) {
            legacyView = mode == Mode.SINGLE ? legacyText() : legacyList();
        }
        return legacyView;
    }

    private TextSetting legacyText() {
        TextSetting text = new TextSetting(getRawName(), "e.g. 5",
                () -> {
                    List<PlotToken> tokens = tokens();
                    return tokens.isEmpty() ? "" : tokens.getFirst().text();
                },
                typed -> {
                    PlotToken token = PlotToken.parse(typed);
                    write(token == null ? List.of() : List.of(token));
                });
        String description = rawDescription();
        return description == null ? text : text.describe(description);
    }

    // keeps blank rows and duplicates, because the old list editor inserts a blank row and edits it by index
    private ListSetting legacyList() {
        ListSetting list = new ListSetting(getRawName(), "Add plot number",
                () -> getter.get().stream().map(PlotSetting::canonicalOrRaw).toList(),
                values -> setter.accept(values.stream().map(PlotSetting::canonicalOrRaw).toList()));
        String description = rawDescription();
        return description == null ? list : list.describe(description);
    }

    private static String canonicalOrRaw(String raw) {
        PlotToken token = PlotToken.parse(raw);
        return token == null ? raw : token.text();
    }
}
