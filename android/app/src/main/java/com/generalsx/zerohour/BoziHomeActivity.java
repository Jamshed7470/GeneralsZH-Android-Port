package com.generalsx.zerohour;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Главный экран BOZI: библиотека игр.
 *
 * <p>Игрок видит список игр и одну кнопку на каждой. Если игра уже на
 * телефоне — она запускается; если нет — скачивается и распаковывается сама,
 * без выбора папок, архиваторов и переноса файлов с компьютера.
 */
public class BoziHomeActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private LinearLayout catalogBox;
    private TextView statusLine;
    /** Игрок ушёл выдавать разрешение — вернётся, и запуск продолжится сам. */
    private boolean pendingLaunchAfterPermission;
    private TextView playButton;
    private boolean installing;
    private boolean pendingLaunchAfterRotation;
    private List<BoziApi.Game> catalog = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BoziConfig.signedIn(this)) {
            BoziAuthActivity.signOut(this);
            return;
        }
        build();
        loadCatalog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPlayButton();
        if (pendingLaunchAfterPermission && haveUserDataAccess()) {
            pendingLaunchAfterPermission = false;
            launchGame();
            return;
        }
        // Вернулись из игры — партия закончилась, сессия снова открыта для
        // входа. Состояние на сервере переключаем здесь, а не в момент выхода
        // из игры: экран игры принадлежит движку, и события выхода у нас нет.
        reportSessionState("lobby");
    }

    private void build() {
        root = BoziUi.screen(this);
        BoziUi.title(this, root, "BOZI");
        BoziUi.label(this, root, "Вы вошли как " + BoziConfig.login(this), BoziUi.MUTED);

        playButton = BoziUi.button(this, root, "Играть", true, v -> launchGame());
        statusLine = BoziUi.label(this, root, "", BoziUi.MUTED);

        BoziUi.button(this, root, "Игроки и сессии", false,
                v -> startActivity(new Intent(this, BoziLobbyActivity.class)));

        BoziUi.sectionTitle(this, root, "Игры");
        catalogBox = new LinearLayout(this);
        catalogBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(catalogBox, BoziUi.params(this, 0, 8));

        BoziUi.sectionTitle(this, root, "Ещё");
        BoziUi.button(this, root, "Настройки движка и графики", false,
                v -> startActivity(new Intent(this, SetupActivity.class)));
        BoziUi.button(this, root, "Выйти из учётной записи", false,
                v -> BoziAuthActivity.signOut(this));

        refreshPlayButton();
    }

    private void refreshPlayButton() {
        boolean ready = BoziInstall.readyToPlay(this);
        playButton.setVisibility(ready ? TextView.VISIBLE : TextView.GONE);
        if (ready) {
            String path = BoziInstall.currentGamePath(this);
            statusLine.setText("Игра готова: " + new File(path == null ? "" : path).getName());
        } else {
            statusLine.setText("Игра ещё не установлена — выберите её в списке ниже.");
        }
    }

    private void loadCatalog() {
        BoziUi.label(this, catalogBox, "Загружаем список игр…", BoziUi.MUTED);
        new Thread(() -> {
            try {
                BoziApi api = new BoziApi(this);
                List<BoziApi.Game> games = api.catalog();
                ui.post(() -> {
                    catalog = games;
                    showCatalog();
                });
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> {
                    catalogBox.removeAllViews();
                    BoziUi.label(this, catalogBox, "Список игр недоступен: " + text, BoziUi.BAD);
                    BoziUi.button(this, catalogBox, "Повторить", false, v -> {
                        catalogBox.removeAllViews();
                        loadCatalog();
                    });
                    if (text.contains("войти")) {
                        BoziUi.button(this, catalogBox, "Войти заново", false,
                                v -> BoziAuthActivity.signOut(this));
                    }
                });
            }
        }, "bozi-catalog").start();
    }

    private void showCatalog() {
        catalogBox.removeAllViews();
        if (catalog.isEmpty()) {
            BoziUi.label(this, catalogBox, "Пока ни одной игры не опубликовано.", BoziUi.MUTED);
            return;
        }
        for (BoziApi.Game game : catalog) {
            LinearLayout card = BoziUi.card(this, catalogBox);
            TextView name = BoziUi.label(this, card, game.title, BoziUi.TEXT);
            name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
            if (!game.subtitle.isEmpty()) {
                BoziUi.label(this, card, game.subtitle, BoziUi.MUTED);
            }

            boolean installed = BoziInstall.installed(this, game.addon ? game.base : game.id);
            String size = game.sizeText();
            if (!game.ready) {
                BoziUi.label(this, card, "Файл ещё не выложен на сервер", BoziUi.BAD);
                continue;
            }
            if (installed && !game.addon) {
                BoziUi.label(this, card, "Установлена · " + size, BoziUi.OK);
                BoziUi.button(this, card, "Играть", true, v -> {
                    selectAndLaunch(game);
                });
            } else {
                TextView action = BoziUi.button(this, card,
                        (game.addon ? "Добавить · " : "Скачать · ") + size, !installed, null);
                action.setOnClickListener(v -> startInstall(game, action));
            }
        }
    }

    /** Делает игру текущей и запускает её. */
    private void selectAndLaunch(BoziApi.Game game) {
        try {
            BoziInstall.configure(this, BoziInstall.gameDir(this, game.id));
        } catch (Exception e) {
            statusLine.setText(BoziAuthActivity.message(e));
            return;
        }
        refreshPlayButton();
        launchGame();
    }

    private void startInstall(BoziApi.Game game, TextView action) {
        if (installing) return;
        installing = true;
        action.setText("Готовим…");
        new Thread(() -> {
            try {
                BoziApi api = new BoziApi(this);
                // Дополнение к Generals (Zero Hour и моды на его основе) без
                // файлов базовой игры не запустится: половина моделей, звуков и
                // карт лежит там. Ставим её сама, не спрашивая — игрок хочет
                // играть, а не разбираться в устройстве сборок.
                if (needsBase(game) && findInstalledBase() == null) {
                    BoziApi.Game base = findCatalogBase();
                    if (base != null) {
                        install(api, base, action, "Базовая игра: ");
                    }
                }
                install(api, game, action, "");
                ui.post(() -> {
                    installing = false;
                    refreshPlayButton();
                    showCatalog();
                });
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> {
                    installing = false;
                    action.setText("Повторить загрузку");
                    statusLine.setText(text);
                });
            }
        }, "bozi-install").start();
    }

    private void install(BoziApi api, BoziApi.Game game, TextView action, String prefix)
            throws java.io.IOException {
        BoziInstall.install(this, api, game, new BoziInstall.Listener() {
            @Override
            public void onStage(String stage, long done, long total) {
                String text;
                if (total > 0) {
                    long percent = Math.min(100, done * 100 / total);
                    text = prefix + stage + " " + percent + "% · "
                            + (done / 1048576) + " из " + (total / 1048576) + " МБ";
                } else {
                    text = prefix + stage + "…";
                }
                ui.post(() -> action.setText(text));
            }

            @Override
            public boolean keepGoing() {
                return !isFinishing();
            }
        });
    }

    private boolean needsBase(BoziApi.Game game) {
        return game.needsBase;
    }

    /** Базовая сборка каталога — та, которой сама ничего не требуется. */
    private BoziApi.Game findCatalogBase() {
        for (BoziApi.Game g : catalog) {
            if (!g.addon && !g.needsBase) return g;
        }
        return null;
    }

    private File findInstalledBase() {
        String path = BoziInstall.currentBasePath(this);
        if (path == null) return null;
        File dir = new File(path);
        return dir.isDirectory() ? dir : null;
    }

    // --- запуск игры ---

    /**
     * Игра идёт только в горизонтальной ориентации, а этот экран —
     * вертикальный. Поворот запрашиваем заранее и ждём подтверждения системы:
     * если запустить игру в момент поворота, её замер размера окна поймает
     * промежуточное состояние и картинка окажется перевёрнутой.
     */
    /**
     * Проверяет доступ к общему хранилищу и создаёт папку данных игры.
     *
     * <p>Движок держит сохранения, настройки и карты в общей папке
     * {@code /storage/emulated/0/Generals} — так их видно любым файловым
     * менеджером, и туда же кладут скачанные карты. Писать в корень общего
     * хранилища Android разрешает только по отдельному разрешению «Доступ ко
     * всем файлам», которое выдаётся руками в настройках системы.
     *
     * <p>Без него игра запускается, но не может создать свою папку и падает с
     * окном «Technical Difficulties» на чёрном экране — по этому окну
     * невозможно догадаться, что дело в разрешении. Раньше разрешение просили
     * только на экране выбора папки с игрой; в BOZI игры ставит само
     * приложение, и тот экран никто не открывает, поэтому новый игрок
     * упирался в это окно на первом же запуске.
     *
     * @return true, если можно запускать игру
     */
    private boolean ensureUserDataAccess() {
        if (!haveUserDataAccess()) {
            askForUserDataAccess();
            return false;
        }
        createUserDataDirs();
        return true;
    }

    private boolean haveUserDataAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void askForUserDataAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // До Android 11 хватает обычного разрешения, системного экрана нет.
            requestPermissions(new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1101);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Нужен доступ к файлам")
                .setMessage("Игра хранит сохранения, настройки и карты в общей папке "
                        + "Generals в памяти телефона. Android разрешает это только по "
                        + "отдельному разрешению.\n\n"
                        + "Сейчас откроются настройки — включите «Доступ ко всем файлам» "
                        + "для BOZI и вернитесь назад. Игра запустится сама.")
                .setPositiveButton("Открыть настройки", (d, which) -> {
                    pendingLaunchAfterPermission = true;
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        // На некоторых прошивках экрана «для приложения» нет —
                        // открываем общий список.
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        } catch (Exception ignored) {
                            pendingLaunchAfterPermission = false;
                            statusLine.setText("Откройте настройки телефона и разрешите "
                                    + "BOZI доступ ко всем файлам");
                        }
                    }
                })
                .setNegativeButton("Не сейчас", null)
                .show();
    }

    /**
     * Создаёт папки данных заранее, не дожидаясь движка.
     *
     * <p>Движок создаёт их сам, но молча: если что-то пошло не так, игрок
     * увидит только окно серьёзной ошибки. Создав их здесь, мы и проверяем
     * право на запись в понятном месте, и оставляем игроку папку, куда он
     * может положить свои карты ещё до первого запуска.
     */
    private void createUserDataDirs() {
        File root = Environment.getExternalStorageDirectory();
        if (root == null) {
            return;
        }
        File generals = new File(root, "Generals");
        new File(generals, "Command and Conquer Generals Data").mkdirs();
        new File(generals, "Command and Conquer Generals Zero Hour Data").mkdirs();
    }

    private void launchGame() {
        if (!BoziInstall.readyToPlay(this)) {
            statusLine.setText("Сначала установите игру");
            return;
        }
        if (!ensureUserDataAccess()) {
            return;
        }
        reportSessionState("playing");
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            startActivity(new Intent(this, GeneralsZHActivity.class));
            return;
        }
        pendingLaunchAfterRotation = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    /**
     * Отмечает состояние своей сессии, если игрок в ней хозяин.
     *
     * <p>Тихо: не получилось — значит, игрок просто не в сессии или сеть
     * моргнула, и ни то ни другое не повод мешать ему играть.
     */
    private void reportSessionState(String state) {
        String code = BoziTunnel.get().status().code;
        if (code.isEmpty()) return;
        new Thread(() -> {
            try {
                new BoziApi(this).setSessionState(code, state);
            } catch (Exception ignored) {
            }
        }, "bozi-state").start();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (pendingLaunchAfterRotation && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            pendingLaunchAfterRotation = false;
            startActivity(new Intent(this, GeneralsZHActivity.class));
            // Возвращаем экран в вертикальное положение: игрок вернётся сюда
            // после выхода из игры, и встречать его боком незачем.
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }
}
