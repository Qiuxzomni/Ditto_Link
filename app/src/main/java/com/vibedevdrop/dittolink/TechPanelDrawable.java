package com.vibedevdrop.dittolink;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Small code-drawn brushed-metal and cyan-grid surface for custom app dialogs. */
final class TechPanelDrawable extends Drawable {
    private final float density;
    private final int accent;
    private final boolean grid;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    TechPanelDrawable(float density, int accent, boolean grid) {
        this.density = density;
        this.accent = accent;
        this.grid = grid;
    }

    @Override public void draw(Canvas canvas) {
        RectF bounds = new RectF(getBounds());
        if (bounds.isEmpty()) return;
        float radius = 14 * density;
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, bounds.top, 0, bounds.bottom,
                new int[]{0xFF4A5B64, 0xFF16232B, 0xFF080E13, 0xFF2E3E47}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bounds, radius, radius, paint);
        paint.setShader(null);
        RectF glass = new RectF(bounds.left + 3 * density, bounds.top + 3 * density,
                bounds.right - 3 * density, bounds.bottom - 3 * density);
        paint.setShader(new LinearGradient(0, glass.top, 0, glass.bottom,
                new int[]{0xF40B242B, 0xF9071319, 0xF90A1920}, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(glass, radius - 3 * density, radius - 3 * density, paint);
        paint.setShader(null);
        if (grid && glass.width() > 34 * density && glass.height() > 34 * density) {
            canvas.save();
            canvas.clipRect(glass.left + 2 * density, glass.top + 2 * density, glass.right - 2 * density, glass.bottom - 2 * density);
            paint.setColor(0x1833D7E8); paint.setStrokeWidth(Math.max(0.5f, density * 0.45f));
            float step = 18 * density;
            for (float x = glass.left + step; x < glass.right; x += step) canvas.drawLine(x, glass.top, x, glass.bottom, paint);
            for (float y = glass.top + step; y < glass.bottom; y += step) canvas.drawLine(glass.left, y, glass.right, y, paint);
            canvas.restore();
        }
        paint.setShader(null); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.1f * density); paint.setColor(0xAA89969B);
        canvas.drawRoundRect(new RectF(bounds.left + 1 * density, bounds.top + 1 * density,
                bounds.right - 1 * density, bounds.bottom - 1 * density), radius, radius, paint);
        paint.setStrokeWidth(1.2f * density); paint.setColor(accent);
        canvas.drawLine(glass.left + 14 * density, glass.top, glass.right - 28 * density, glass.top, paint);
        paint.setColor(0x8845F1FF); paint.setStrokeWidth(0.8f * density);
        canvas.drawLine(glass.left + 8 * density, bounds.bottom - 2.4f * density,
                glass.right - 16 * density, bounds.bottom - 2.4f * density, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE); paint.setAlpha(70);
        canvas.drawCircle(bounds.left + 9 * density, bounds.top + 9 * density, 1.5f * density, paint);
        canvas.drawCircle(bounds.right - 9 * density, bounds.bottom - 9 * density, 1.5f * density, paint);
        paint.setColor(accent); paint.setAlpha(180);
        canvas.drawRect(bounds.right - 22 * density, bounds.top + 1.5f * density,
                bounds.right - 8 * density, bounds.top + 2.7f * density, paint);
    }

    @Override public void setAlpha(int alpha) { }
    @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) { }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
}
