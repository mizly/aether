package dev.aether.ui.gui;

// layout-unit rectangle; a side that is not positive makes it empty
public record Rect(float x, float y, float w, float h) {
    public static final Rect EMPTY = new Rect(0f, 0f, 0f, 0f);

    public static Rect ofEdges(float left, float top, float right, float bottom) {
        return new Rect(left, top, right - left, bottom - top);
    }

    public float right() {
        return x + w;
    }

    public float bottom() {
        return y + h;
    }

    public float centerX() {
        return x + w / 2f;
    }

    public float centerY() {
        return y + h / 2f;
    }

    public boolean isEmpty() {
        return w <= 0f || h <= 0f;
    }

    // half-open on the far edges so two touching rects never both claim a point
    public boolean contains(float px, float py) {
        return !isEmpty() && px >= x && py >= y && px < x + w && py < y + h;
    }

    public Rect intersect(Rect other) {
        float left = Math.max(x, other.x);
        float top = Math.max(y, other.y);
        float right = Math.min(right(), other.right());
        float bottom = Math.min(bottom(), other.bottom());
        return right <= left || bottom <= top ? EMPTY : ofEdges(left, top, right, bottom);
    }

    public Rect offset(float dx, float dy) {
        return new Rect(x + dx, y + dy, w, h);
    }

    public Rect inset(float amount) {
        return inset(amount, amount, amount, amount);
    }

    public Rect inset(float left, float top, float right, float bottom) {
        return new Rect(x + left, y + top, Math.max(0f, w - left - right), Math.max(0f, h - top - bottom));
    }
}
