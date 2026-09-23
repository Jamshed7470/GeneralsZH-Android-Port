package com.generalsx.zerohour;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Обложки игр в карточках. Админ ставит картинку в админке, сервер отдаёт её
 * по имени вида «pc-x.jpg?v=…». Имя меняется вместе с картинкой, поэтому
 * скачанную один раз держим в кеше и больше не спрашиваем.
 */
final class BoziCovers {
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private BoziCovers() {}

    /** Ставит обложку в карточку, созданную {@link BoziUi#gameCard}. */
    static void apply(Activity activity, android.view.View card, String cover) {
        if (cover == null || cover.isEmpty() || !(card instanceof LinearLayout)) return;
        android.view.View first = ((LinearLayout) card).getChildAt(0);
        if (!(first instanceof ImageView)) return;
        ImageView thumb = (ImageView) first;
        thumb.setTag(cover);
        File cached = new File(new File(activity.getCacheDir(), "covers"), cover.replaceAll("[^A-Za-z0-9._-]", "_"));
        POOL.execute(() -> {
            try {
                if (!cached.isFile()) {
                    byte[] data = new BoziApi(activity).cover(cover);
                    cached.getParentFile().mkdirs();
                    File tmp = new File(cached.getPath() + ".part");
                    try (FileOutputStream out = new FileOutputStream(tmp)) {
                        out.write(data);
                    }
                    tmp.renameTo(cached);
                }
                Bitmap bmp = decode(cached, 256);
                if (bmp == null) return;
                UI.post(() -> {
                    // Карточку могли перерисовать под другую игру, пока качали.
                    if (cover.equals(thumb.getTag())) thumb.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
                // Без обложки карточка остаётся с пустым квадратом — не беда.
            }
        });
    }

    /** Уменьшает картинку при чтении: в карточке нужно 58 dp, а не 4K. */
    private static Bitmap decode(File f, int maxSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }
}
