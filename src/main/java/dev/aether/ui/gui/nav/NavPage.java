package dev.aether.ui.gui.nav;

import dev.aether.ui.gui.Icon;
import dev.aether.ui.settings.ModulesTab;
import dev.aether.ui.settings.Setting;
import dev.aether.ui.settings.SettingGroup;
import dev.aether.util.AetherLang;

import java.util.ArrayList;
import java.util.List;

// one navigable page: module pages compose blocks from registry subtabs, custom pages (Appearance,
// Profiles) are drawn by the styles. nothing here copies a group list; every read goes to the live subtab
public final class NavPage {

    public enum Kind { MODULE, CUSTOM }

    // PAGE_TOGGLE: the group's switch is the page switch, so it draws none and always shows its children
    public enum GroupMode { ALWAYS_ON, TOGGLE, PAGE_TOGGLE }

    private final Placement.PageSpec spec;
    private final String categoryId;
    private final String rawName;
    private final String rawDescription;
    private final Icon icon;
    private final int order;
    private final Kind kind;
    private final List<PageBlock> blocks;
    private final PageToggle toggle;
    private final boolean fallthrough;

    NavPage(Placement.PageSpec spec, String categoryId, String rawName, String rawDescription, Icon icon, int order,
            Kind kind, List<PageBlock> blocks, PageToggle toggle, boolean fallthrough) {
        this.spec = spec;
        this.categoryId = categoryId;
        this.rawName = rawName;
        this.rawDescription = rawDescription;
        this.icon = icon;
        this.order = order;
        this.kind = kind;
        this.blocks = List.copyOf(blocks);
        this.toggle = toggle;
        this.fallthrough = fallthrough;
    }

    public String id() {
        return spec.id();
    }

    public String categoryId() {
        return categoryId;
    }

    public String rawName() {
        return rawName;
    }

    public String name() {
        return AetherLang.localize(rawName);
    }

    public String rawDescription() {
        return rawDescription;
    }

    public String description() {
        return rawDescription == null ? "" : AetherLang.localize(rawDescription);
    }

    // null when neither the placement nor the subtab gives one
    public Icon icon() {
        return icon;
    }

    public int order() {
        return order;
    }

    public Kind kind() {
        return kind;
    }

    public boolean custom() {
        return kind == Kind.CUSTOM;
    }

    // true for a page made only because nothing placed its subtab
    public boolean fallthrough() {
        return fallthrough;
    }

    public List<PageBlock> blocks() {
        return blocks;
    }

    // null when the page has no switch
    public PageToggle toggle() {
        return toggle;
    }

    public boolean hasToggle() {
        return toggle != null;
    }

    public boolean isEnabled() {
        return toggle == null || toggle.isOn();
    }

    // parent page id for a feature that only runs inside another (shown with "Needs <parent>"), or null
    public String requires() {
        return spec.requires();
    }

    public List<String> aliases() {
        return spec.aliases();
    }

    public List<ModulesTab.SubTab> sources() {
        List<ModulesTab.SubTab> out = new ArrayList<>();
        for (PageBlock block : blocks) {
            if (block.source() != null && !out.contains(block.source())) {
                out.add(block.source());
            }
        }
        return out;
    }

    // every group on the page in drawing order, read live from the subtabs
    public List<SettingGroup> groups() {
        List<SettingGroup> out = new ArrayList<>();
        for (PageBlock block : blocks) {
            out.addAll(block.groups());
        }
        return out;
    }

    public GroupMode groupMode(SettingGroup group) {
        if (group.isAlwaysOn()) {
            return GroupMode.ALWAYS_ON;
        }
        if ((toggle != null && toggle.isGroup(group)) || spec.pageToggleGroups().contains(group.getRawName())) {
            return GroupMode.PAGE_TOGGLE;
        }
        return GroupMode.TOGGLE;
    }

    public boolean hides(SettingGroup group, Setting setting) {
        return spec.hiddenSettings().contains(new Placement.HiddenSetting(group.getRawName(), setting.getRawName()));
    }

    // what a settings list draws: visible right now and not hidden by the placement
    public boolean shows(SettingGroup group, Setting setting) {
        return setting.isVisible() && !hides(group, setting);
    }

    // the page that owns this knob when the copy here is a mirror, else null
    public String mirrorOf(SettingGroup group, Setting setting) {
        String canonical = setting == null ? null : setting.mirrorOf();
        if (canonical == null) {
            canonical = group.mirrorOf();
        }
        if (canonical == null) {
            String settingRaw = setting == null ? null : setting.getRawName();
            for (Placement.Mirror mirror : spec.mirrors()) {
                if (setting == null ? mirror.settingRawName() == null && mirror.matches(group.getRawName(), null)
                        : mirror.matches(group.getRawName(), settingRaw)) {
                    canonical = mirror.canonicalPageId();
                    break;
                }
            }
        }
        return canonical == null || canonical.equals(id()) ? null : canonical;
    }

    Placement.PageSpec spec() {
        return spec;
    }

    @Override
    public String toString() {
        return "NavPage[" + id() + "]";
    }
}
