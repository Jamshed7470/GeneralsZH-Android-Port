package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
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
    private void launchGame() {
        if (!BoziInstall.readyToPlay(this)) {
            statusLine.setText("Сначала установите игру");
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
