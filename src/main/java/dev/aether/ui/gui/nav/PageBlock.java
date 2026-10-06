package dev.aether.ui.gui.nav;

import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

// one source subtab's share of a page. merged pages draw each block under a header with its own switch;
// a single-source page has one block without a header. groups() reads the subtab live on every call
public final class PageBlock {
    private final String id;
    private final ModulesTab.SubTab source;
    private final Predicate<SettingGroup> filter;
    private final PageToggle toggle;
    private final boolean headed;

    PageBlock(String id, ModulesTab.SubTab source, Predicate<SettingGroup> filter, PageToggle toggle, boolean headed) {
        this.id = id;
        this.source = source;
        this.filter = filter;
        this.toggle = toggle;
        this.headed = headed;
    }

    public String id() {
        return id;
    }

    public ModulesTab.SubTab source() {
        return source;
    }

    public String title() {
        return source == null ? "" : source.name();
    }

    public String rawTitle() {
        return source == null ? "" : source.rawName();
    }

    public String description() {
        return source == null || source.rawDescription() == null ? "" : AetherLang.localize(source.rawDescription());
    }

    // null when the block has no switch of its own (single-source pages carry it as the page switch)
    public PageToggle toggle() {
        return toggle;
    }

    public boolean headed() {
        return headed;
    }

    public List<SettingGroup> groups() {
        List<SettingGroup> out = new ArrayList<>();
        if (source == null) {
            return out;
        }
        for (SettingGroup group : source.groups()) {
            if (filter.test(group)) {
                out.add(group);
            }
        }
        return out;
    }
}
