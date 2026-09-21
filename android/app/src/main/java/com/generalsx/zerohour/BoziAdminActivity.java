package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Админка платформы прямо в приложении.
 *
 * <p>До этого она жила только в вебе, за самоподписанным сертификатом: чтобы
 * закрыть зависшую партию, владельцу сервера приходилось открывать браузер,
 * соглашаться на предупреждение о сертификате и вводить пароль заново. Здесь
 * то же самое доступно с того же телефона, на котором играют, и по уже
 * выданному токену.
 *
 * <p>Экран открывается только администратору — признак приходит с сервера
 * вместе с профилем, поэтому подделать его на телефоне нечем.
 */
public class BoziAdminActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView statusLine;
    private LinearLayout statsRow;
    private LinearLayout roomsBox;
    private LinearLayout buildsBox;
    private LinearLayout usersBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BoziConfig.signedIn(this)) {
            BoziAuthActivity.signOut(this);
            return;
        }
        build();
        reload();
    }

    private void build() {
        LinearLayout root = BoziUi.screen(this, R.drawable.bozi_bg_admin);
        LinearLayout head = BoziUi.header(this, root, "запрашиваем состояние…", "Сервер",
                initials(BoziConfig.login(this)), null);
        statusLine = (TextView) head.getChildAt(0);

        statsRow = BoziUi.row(this, root);

        BoziUi.sectionTitle(this, root, "Сессии");
        roomsBox = new LinearLayout(this);
        roomsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(roomsBox, BoziUi.params(this, 0, 8));

        BoziUi.sectionTitle(this, root, "Каталог сборок");
        buildsBox = new LinearLayout(this);
        buildsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(buildsBox, BoziUi.params(this, 0, 8));

        BoziUi.sectionTitle(this, root, "Игроки");
        usersBox = new LinearLayout(this);
        usersBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(usersBox, BoziUi.params(this, 0, 8));

        BoziUi.button(this, root, "Обновить", false, v -> reload());
    }

    private void reload() {
        new Thread(() -> {
            try {
                BoziApi.Overview overview = new BoziApi(this).adminOverview();
                ui.post(() -> show(overview));
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(text));
            }
        }, "bozi-admin").start();
    }

    private void show(BoziApi.Overview overview) {
        statusLine.setText("Сервер работает " + uptime(overview.uptimeSeconds));

        statsRow.removeAllViews();
        BoziUi.tile(this, statsRow, String.valueOf(overview.online), "в сети");
        BoziUi.tile(this, statsRow, String.valueOf(overview.playing), "в бою");
        BoziUi.tile(this, statsRow, String.valueOf(overview.rooms), "сессий");
        BoziUi.tile(this, statsRow, String.valueOf(overview.users), "всего");

        roomsBox.removeAllViews();
        if (overview.rooms == 0) {
            BoziUi.label(this, roomsBox, "Сейчас сессий нет.", BoziUi.MUTED);
        } else {
            for (BoziApi.Session room : overview.roomList) {
                LinearLayout card = BoziUi.card(this, roomsBox);
                LinearLayout head = BoziUi.row(this, card);
                TextView title = new TextView(this);
                title.setText(room.title.isEmpty() ? room.code : room.title);
                title.setTextColor(BoziUi.TEXT);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                head.addView(title, BoziUi.grow());
                boolean playing = "playing".equals(room.state);
                BoziUi.pill(this, head, playing ? "идёт бой" : "сбор",
                        playing ? BoziUi.ACCENT : BoziUi.OK);

                BoziUi.label(this, card, room.code + " · хост " + room.host
                        + " · " + room.players + " из " + room.maxPlayers, BoziUi.MUTED);
                BoziUi.button(this, card, "Закрыть сессию", false, v -> confirmClose(room));
            }
        }

        buildsBox.removeAllViews();
        for (BoziApi.Game game : overview.catalog) {
            LinearLayout row = BoziUi.listRow(this, buildsBox, game.title,
                    game.sizeText() + (game.ready ? "" : " · файла нет на сервере"),
                    BoziUi.TEXT, null);
            BoziUi.pill(this, row, game.ready ? "выложена" : "черновик",
                    game.ready ? BoziUi.OK : BoziUi.BAD);
        }

        usersBox.removeAllViews();
        for (BoziApi.Account account : overview.accounts) {
            LinearLayout row = BoziUi.listRow(this, usersBox, account.login,
                    account.games + " партий · " + hours(account.playedSeconds) + " в игре",
                    account.banned ? BoziUi.BAD : BoziUi.TEXT,
                    v -> confirmBan(account));
            if (account.admin) {
                BoziUi.pill(this, row, "админ", BoziUi.ACCENT);
            }
            if (account.online) {
                BoziUi.pill(this, row, "в сети", BoziUi.OK);
            }
        }
    }

    /**
     * Закрытие сессии спрашивает подтверждение.
     *
     * <p>Для игроков внутри это выглядит как обрыв посреди боя, а кнопка
     * стоит вплотную к остальным строкам списка — промах пальцем не должен
     * стоить кому-то партии.
     */
    private void confirmClose(BoziApi.Session room) {
        new AlertDialog.Builder(this)
                .setTitle("Закрыть " + room.code + "?")
                .setMessage("Все, кто сейчас в этой сессии, вылетят из неё. "
                        + (("playing".equals(room.state)) ? "Сейчас там идёт бой." : ""))
                .setPositiveButton("Закрыть", (d, which) -> act(
                        () -> new BoziApi(this).adminCloseRoom(room.code)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void confirmBan(BoziApi.Account account) {
        boolean banned = account.banned;
        new AlertDialog.Builder(this)
                .setTitle(account.login)
                .setMessage(banned
                        ? "Снять запрет? Игрок снова сможет входить и скачивать сборки."
                        : "Закрыть доступ? Вход и скачивание перестанут работать, "
                          + "учётная запись останется.")
                .setPositiveButton(banned ? "Снять запрет" : "Закрыть доступ", (d, which) -> act(
                        () -> new BoziApi(this).adminUser(account.login,
                                banned ? "unban" : "ban")))
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Выполняет действие админки и обновляет экран его результатом. */
    private void act(ThrowingAction action) {
        new Thread(() -> {
            try {
                action.run();
                ui.post(this::reload);
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(text));
            }
        }, "bozi-admin-act").start();
    }

    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static String uptime(long seconds) {
        if (seconds < 3600) return seconds / 60 + " мин";
        if (seconds < 86400) return seconds / 3600 + " ч";
        return seconds / 86400 + " дн";
    }

    private static String hours(long seconds) {
        if (seconds < 3600) return Math.max(0, seconds / 60) + " м";
        return (seconds / 3600) + " ч";
    }

    private static String initials(String login) {
        if (login == null || login.isEmpty()) return "?";
        return login.substring(0, Math.min(2, login.length())).toUpperCase();
    }
}
