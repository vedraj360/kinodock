package com.vdx.kinodock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Bitmap;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Transparent, white marks made for the single-row favorites dock. */
final class FavoriteLogos {
    private FavoriteLogos() { }

    static boolean supports(String pkg) {
        switch (pkg) {
            case "com.netflix.ninja":
            case "in.startv.hotstar":
            case "org.smarttube.stable":
            case "com.crunchyroll.crunchyroid":
            case "com.vdx.binzo.debug":
            case "com.internet.tvbrowser":
            case "org.videolan.vlc":
            case "com.google.android.youtube.tv":
            case "com.android.vending":
            case "com.mxtech.videoplayer.television":
            case "ch.protonvpn.android":
                return true;
            default:
                return false;
        }
    }

    static Drawable forPackage(Context context, String pkg, String label) {
        int id;
        switch (pkg) {
            case "com.netflix.ninja": id = R.drawable.fav_netflix; break;
            case "in.startv.hotstar": id = R.drawable.fav_jiohotstar; break;
            case "org.smarttube.stable": id = R.drawable.fav_smarttube; break;
            case "com.crunchyroll.crunchyroid": id = R.drawable.fav_crunchyroll; break;
            case "com.vdx.binzo.debug": id = R.drawable.fav_binzo; break;
            case "com.internet.tvbrowser": id = R.drawable.fav_browser; break;
            case "org.videolan.vlc": id = R.drawable.fav_vlc; break;
            case "com.google.android.youtube.tv": id = R.drawable.fav_youtube; break;
            case "com.android.vending": id = R.drawable.fav_googleplay; break;
            case "com.mxtech.videoplayer.television": id = R.drawable.fav_mxplayer; break;
            case "ch.protonvpn.android": id = R.drawable.fav_protonvpn; break;
            default: return new Lettermark(context, label);
        }
        return context.getDrawable(id);
    }

    /** Brand accent used only while a monochrome Home favourite is focused; unknown apps remain white. */
    static int focusColor(String pkg) {
        switch (pkg) {
            case "com.netflix.ninja": return 0xFFE50914;
            case "org.smarttube.stable":
            case "com.google.android.youtube.tv": return 0xFFFF2638;
            case "com.crunchyroll.crunchyroid": return 0xFFF47521;
            case "com.internet.tvbrowser": return 0xFFE53935;
            case "org.videolan.vlc": return 0xFFFF7900;
            case "com.android.vending": return 0xFF35A853;
            case "com.mxtech.videoplayer.television": return 0xFF28A6E8;
            case "ch.protonvpn.android": return 0xFF7B61FF;
            case "com.vdx.binzo.debug": return 0xFF35B86B;
            default: return Color.WHITE;
        }
    }

    static Drawable jioHotstarFocusDrawable(Context context, Drawable source) {
        return new PinkPurpleDrawable(source, context.getResources().getDisplayMetrics().density);
    }

    /** Renders JioHotstar's white mark through a restrained pink-to-purple brand gradient. */
    private static final class PinkPurpleDrawable extends Drawable {
        private final Bitmap mask;
        private final Paint gradient = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        PinkPurpleDrawable(Drawable source, float density) {
            int width = Math.max(1, source.getIntrinsicWidth() > 0
                    ? source.getIntrinsicWidth() : Math.round(96f * density));
            int height = Math.max(1, source.getIntrinsicHeight() > 0
                    ? source.getIntrinsicHeight() : Math.round(96f * density));
            mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(mask);
            source.setBounds(0, 0, width, height);
            source.draw(canvas);
            gradient.setShader(new LinearGradient(0, 0, width, height,
                    new int[]{0xFFFF5BA8, 0xFFB64DDF, 0xFF765BFF}, null, Shader.TileMode.CLAMP));
            maskPaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            int save = canvas.saveLayer(b.left, b.top, b.right, b.bottom, null);
            canvas.drawRect(b, gradient);
            canvas.drawBitmap(mask, null, b, maskPaint);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { gradient.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { gradient.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return mask.getWidth(); }
        @Override public int getIntrinsicHeight() { return mask.getHeight(); }
    }

    /** A quiet white initial keeps newly added apps in the same visual style until a real mark is available. */
    private static final class Lettermark extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String initial;
        private final float density;

        Lettermark(Context context, String label) {
            String value = label == null ? "" : label.trim();
            initial = value.isEmpty() ? "•" : value.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
            density = context.getResources().getDisplayMetrics().density;
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        }

        @Override public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setTextSize(Math.min(68f * density, b.height() * 0.78f));
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(initial, b.exactCenterX(), b.exactCenterY() - (fm.ascent + fm.descent) / 2f, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
