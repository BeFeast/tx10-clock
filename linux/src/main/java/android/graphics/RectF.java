package android.graphics;

/** Linux shim: android.graphics.RectF over java.awt.geom.RoundRectangle2D inputs. */
public final class RectF {

    public float left;
    public float top;
    public float right;
    public float bottom;

    public RectF(float left, float top, float right, float bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public float width() {
        return right - left;
    }

    public float height() {
        return bottom - top;
    }
}
