package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/**
 * Установка игры на телефон: скачать, сверить, распаковать, прописать пути.
 *
 * <p>Игрок не делает ничего руками — ни выбора папок, ни распаковки
 * архиваторами. Файлы приходят с нашего сервера, поэтому у всех игроков
 * гарантированно одна и та же версия: расхождение сборок в Generals означает
 * рассинхрон партии на первой же минуте.
 *
 * <p>Все методы долгие и вызываются из фонового потока.
 */
public final class BoziInstall {

    /** Что сейчас происходит — для полоски на экране. */
    public interface Listener {
        /**
         * @param stage  что делаем: «Скачиваем», «Проверяем», «Распаковываем»
         * @param done   сделано байт (или файлов)
         * @param total  сколько всего, 0 если неизвестно
         */
        void onStage(String stage, long done, long total);

        /** @return true, чтобы продолжать; false — прервать установку */
        boolean keepGoing();
    }

    private BoziInstall() {}

    /** Куда кладём распакованные игры. */
    public static File gamesRoot(Context context) {
        File root = context.getExternalFilesDir("games");
        if (root != null && !root.exists()) {
            //noinspection ResultOfMethodCallIgnored
            root.mkdirs();
        }
        return root;
    }

    public static File gameDir(Context context, String id) {
        return new File(gamesRoot(context), id);
    }

    /**
     * Установлена ли игра.
     *
     * <p>Признаком считаем не саму папку, а наличие архива данных: папка может
     * остаться от прерванной распаковки, и тогда «установлено» было бы враньём.
     */
    public static boolean installed(Context context, String id) {
        File dir = gameDir(context, id);
        return dir.isDirectory() && (hasFile(dir, "INIZH.big") || hasFile(dir, "INI.big"));
    }

    private static boolean hasFile(File dir, String name) {
        File direct = new File(dir, name);
        if (direct.isFile()) return true;
        // Архив мог распаковаться в подпапку — ищем на один уровень вглубь.
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && new File(child, name).isFile()) return true;
            }
        }
        return false;
    }

    /** Папка, в которой реально лежат .big — сама распакованная или её подпапка. */
    public static File resolveDataDir(File dir, String marker) {
        if (new File(dir, marker).isFile()) return dir;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && new File(child, marker).isFile()) return child;
            }
        }
        return dir;
    }

    /**
     * Ставит игру: скачивает архив, сверяет контрольную сумму, распаковывает и
     * прописывает пути, по которым движок найдёт данные.
     */
    public static void install(Context context, BoziApi api, BoziApi.Game game, Listener listener)
            throws IOException {
        File cache = new File(context.getExternalCacheDir(), "downloads");
        if (!cache.exists() && !cache.mkdirs()) {
            throw new IOException("не создать папку загрузок");
        }
        File archive = new File(cache, game.id + ".tzst");

        long needed = (game.unpackedMb + Math.max(game.sizeBytes / (1024 * 1024), 0) + 300) * 1024L * 1024L;
        File root = gamesRoot(context);
        if (root == null) throw new IOException("нет доступа к памяти телефона");
        if (root.getUsableSpace() < needed) {
            throw new IOException("не хватает места: нужно около " + (needed / (1024 * 1024)) + " МБ");
        }

        listener.onStage("Скачиваем", 0, game.sizeBytes);
        String declaredSum = api.download(game.id, archive, (done, total) -> {
            listener.onStage("Скачиваем", done, total);
            return listener.keepGoing();
        });

        if (!declaredSum.isEmpty()) {
            listener.onStage("Проверяем", 0, archive.length());
            String actual = sha256(archive, listener);
            if (!actual.equalsIgnoreCase(declaredSum)) {
                // Битый архив надо удалить целиком: иначе докачка продолжит
                // портить и следующую попытку — она дописывает в конец.
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
                throw new IOException("файл скачался с ошибкой, попробуйте ещё раз");
            }
        }

        File target = gameDir(context, game.id);
        if (game.addon && !game.base.isEmpty()) {
            // Надстройка (мод) кладётся поверх своей сборки, а не рядом.
            target = gameDir(context, game.base);
            if (!target.isDirectory()) {
                throw new IOException("сначала нужно установить основную игру");
            }
        }
        listener.onStage("Распаковываем", 0, game.unpackedMb * 1024L * 1024L);
        extract(archive, target, game.unpackedMb * 1024L * 1024L, listener);
        //noinspection ResultOfMethodCallIgnored
        archive.delete();

        listener.onStage("Настраиваем", 0, 0);
        configure(context, target);
    }

    private static String sha256(File file, Listener listener) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = file.length();
            long done = 0;
            long lastTick = 0;
            try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                    done += n;
                    if (System.currentTimeMillis() - lastTick > 250) {
                        lastTick = System.currentTimeMillis();
                        listener.onStage("Проверяем", done, total);
                        if (!listener.keepGoing()) throw new IOException("установка отменена");
                    }
                }
            }
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) {
                out.append(Character.forDigit((b >> 4) & 0xf, 16));
                out.append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /**
     * Распаковывает tar+zstd.
     *
     * <p>Пути внутри архива проверяются: запись, уводящая за пределы папки
     * назначения («..» в имени), отбрасывается. Архивы собираем мы сами, но
     * распаковщик, который верит содержимому архива на слово, — это дыра
     * независимо от того, кто его наполняет.
     */
    private static void extract(File archive, File target, long expectedBytes, Listener listener)
            throws IOException {
        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("не создать папку игры");
        }
        String targetPath = target.getCanonicalPath() + File.separator;
        long done = 0;
        long lastTick = 0;

        try (InputStream raw = new BufferedInputStream(new FileInputStream(archive), 1 << 16);
             ZstdCompressorInputStream zstd = new ZstdCompressorInputStream(raw);
             TarArchiveInputStream tar = new TarArchiveInputStream(zstd)) {
            TarArchiveEntry entry;
            byte[] buf = new byte[1 << 16];
            while ((entry = tar.getNextTarEntry()) != null) {
                File out = new File(target, entry.getName());
                if (!out.getCanonicalPath().startsWith(targetPath)) {
                    continue; // запись уводит за пределы папки — пропускаем
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("не создать папку " + parent.getName());
                }
                try (OutputStream sink = new FileOutputStream(out)) {
                    int n;
                    while ((n = tar.read(buf)) > 0) {
                        sink.write(buf, 0, n);
                        done += n;
                        if (System.currentTimeMillis() - lastTick > 250) {
                            lastTick = System.currentTimeMillis();
                            listener.onStage("Распаковываем", done, expectedBytes);
                            if (!listener.keepGoing()) throw new IOException("установка отменена");
                        }
                    }
                }
            }
        }
        listener.onStage("Распаковываем", done, expectedBytes);
    }

    /**
     * Прописывает пути, по которым движок ищет данные.
     *
     * <p>Пути живут в трёх местах, и все три обязательны: настройки экрана
     * настроек (SharedPreferences), текстовый файл рядом с приложением — его
     * читает нативная часть, которая к настройкам Android доступа не имеет, —
     * и, для дополнения, отдельный файл с путём к базовой игре.
     */
    public static void configure(Context context, File installedDir) throws IOException {
        File zh = resolveDataDir(installedDir, "INIZH.big");
        boolean isExpansion = new File(zh, "INIZH.big").isFile();
        if (isExpansion) {
            saveGamePath(context, zh);
            // Дополнению нужны и файлы базовой игры: половина моделей, звуков и
            // карт лежит именно там. Ищем её среди уже установленных.
            File base = findBaseGame(context);
            if (base != null) saveBasePath(context, base);
            return;
        }
        File plain = resolveDataDir(installedDir, "INI.big");
        if (new File(plain, "INI.big").isFile()) {
            // Это базовая Generals: она и сама по себе игра, и источник данных
            // для дополнения. Записываем её обоими путями, если дополнения ещё
            // нет — иначе только как базу.
            saveBasePath(context, plain);
            if (currentGamePath(context) == null) saveGamePath(context, plain);
        }
    }

    /** Ищет установленную базовую Generals среди всех установленных игр. */
    private static File findBaseGame(Context context) {
        File root = gamesRoot(context);
        if (root == null) return null;
        File[] dirs = root.listFiles();
        if (dirs == null) return null;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File data = resolveDataDir(dir, "INI.big");
            if (new File(data, "INI.big").isFile() && !new File(data, "INIZH.big").isFile()) {
                return data;
            }
        }
        return null;
    }

    public static String currentGamePath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SetupActivity.PREF_GAME_PATH, null);
    }

    public static String currentBasePath(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(SetupActivity.PREF_BASE_GENERALS_PATH, null);
    }

    /** Готова ли игра к запуску: путь задан и по нему действительно есть данные. */
    public static boolean readyToPlay(Context context) {
        String path = currentGamePath(context);
        return path != null && SetupActivity.isValidGameFolder(new File(path));
    }

    private static void saveGamePath(Context context, File dir) throws IOException {
        String path = dir.getAbsolutePath();
        context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(SetupActivity.PREF_GAME_PATH, path).apply();
        writeMarker(new File(context.getFilesDir(), "gamedata_path.txt"), path);
        File bundled = context.getExternalFilesDir(null);
        if (bundled != null) {
            // dxvk.conf, DefaultOptions.ini и шрифты движок читает относительно
            // рабочей папки, а рабочей становится папка игры.
            SetupActivity.copyBundledRuntimeIfMissing(bundled, path);
        }
    }

    private static void saveBasePath(Context context, File dir) throws IOException {
        String path = dir.getAbsolutePath();
        context.getSharedPreferences(SetupActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(SetupActivity.PREF_BASE_GENERALS_PATH, path).apply();
        writeMarker(new File(context.getFilesDir(), "generals_base_path.txt"), path);
    }

    private static void writeMarker(File file, String path) throws IOException {
        try (FileWriter w = new FileWriter(file, false)) {
            w.write(path);
            w.write("\n");
        }
    }
}
