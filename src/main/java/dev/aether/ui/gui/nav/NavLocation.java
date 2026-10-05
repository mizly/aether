package dev.aether.ui.gui.nav;

// where the gui is: home, a category, or a page, optionally scrolled to an anchor (GroupKey/SettingKey
// anchor strings or a section name). keyed by stable ids so it survives registry rebuilds
public record NavLocation(String categoryId, String pageId, String anchor) {
    public static final NavLocation HOME = new NavLocation(null, null, null);

    public static NavLocation category(String categoryId) {
        return new NavLocation(categoryId, null, null);
    }

    public static NavLocation page(String categoryId, String pageId) {
        return new NavLocation(categoryId, pageId, null);
    }

    public NavLocation withAnchor(String anchor) {
        return new NavLocation(categoryId, pageId, anchor);
    }

    public boolean isHome() {
        return categoryId == null;
    }

    public boolean isCategory() {
        return categoryId != null && pageId == null;
    }

    public boolean isPage() {
        return pageId != null;
    }

    // one hierarchy level up: page to its category, category to home
    public NavLocation up() {
        if (isPage()) {
            return category(categoryId);
        }
        return HOME;
    }
}
