package com.generalsx.zerohour;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

/**
 * Компьютерные игры в BOZI: Need for Speed и другие, которые идут не нативно,
 * а через Wine.
 *
 * <p>Wine живёт в отдельном приложении — «движке» ({@code com.winlator}). В
 * сам BOZI его не встроить: системные файлы Wine собраны под путь
 * {@code /data/data/com.winlator}, и под другим именем пакета не запускаются.
 * Поэтому BOZI один раз скачивает движок со своего сервера, ставит его, а
 * дальше просто просит его запустить игру — игрок видит одну кнопку
 * «Играть».
 *
 * <p>Сами игры сервер не раздаёт: движок находит на телефоне папку, которую
 * положил пользователь.
 */
final class BoziPcGames {
    static final String ENGINE_PACKAGE = "com.winlator";
    static final String ENGINE_ID = "bozi-engine";
    private static final String ACTION_PLAY = "com.bozi.action.PLAY";

    /** Компьютерная игра, которую умеет запускать движок. */
    static final class PcGame {
        final String id, title, subtitle, howToGet;
        PcGame(String id, String title, String subtitle, String howToGet) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.howToGet = howToGet;
        }
    }

    static final PcGame[] GAMES = {
            // Steam ставится с сайта Valve при первом запуске; игрок входит
            // в свой аккаунт и качает купленные игры сам.
            new PcGame("steam", "Steam",
                    "Ваша библиотека Steam на телефоне · касание работает как мышь",
                    "Войдите в свой аккаунт Steam и установите свои игры."),
            new PcGame("nfsmw", "Need for Speed Most Wanted",
                    "Гонки по открытому городу · геймпад на экране",
                    "Нужна своя копия игры: скопируйте папку с NFS13.exe в «Загрузки» телефона."),
    };

    private BoziPcGames() {}

    /** @return versionCode установленного движка или -1, если его нет */
    static long installedEngineVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(ENGINE_PACKAGE, 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    static void play(Activity activity, PcGame game) throws ActivityNotFoundException {
        Intent intent = new Intent(ACTION_PLAY);
        intent.setPackage(ENGINE_PACKAGE);
        intent.putExtra("game", game.id);
        activity.startActivity(intent);
    }

    interface Progress {
        void onProgress(long done, long total);
    }

    /**
     * Скачивает движок в кеш (с докачкой) и возвращает файл. Вызывать не на
     * главном потоке.
     */
    static File download(Context context, BoziApi api, BoziApi.Game engine, Progress progress)
            throws IOException {
        File dir = new File(context.getCacheDir(), "engine");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("нет места для загрузки");
        // Версия в имени: недокачанный файл старой версии не склеится с новой.
        File apk = new File(dir, "bozi-engine-" + engine.version + ".apk");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) if (!f.equals(apk)) f.delete();
        if (apk.isFile() && apk.length() == engine.sizeBytes) return apk;

        api.download(ENGINE_ID, apk, (done, total) -> {
            progress.onProgress(done, total);
            return true;
        });
        if (engine.sizeBytes > 0 && apk.length() != engine.sizeBytes) {
            throw new IOException("движок скачался не полностью — нажмите ещё раз, докачаем");
        }
        return apk;
    }

    /**
     * Открывает системную установку APK. Если BOZI ещё не разрешено ставить
     * приложения, сначала ведёт в настройки этого разрешения.
     *
     * @return false, если пришлось отправить в настройки
     */
    static boolean openInstaller(Activity activity, File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            return false;
        }
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(install);
        return true;
    }
}
