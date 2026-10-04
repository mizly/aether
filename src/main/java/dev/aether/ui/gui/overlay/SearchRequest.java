package dev.aether.ui.gui.overlay;

// opens the command palette; the style's palette reads the search index itself
public record SearchRequest(String query) {
    public static SearchRequest of(String query) {
        return new SearchRequest(query == null ? "" : query);
    }
}
