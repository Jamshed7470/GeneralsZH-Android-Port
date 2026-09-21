package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Лобби платформы: кто сейчас в сети, какие игры собираются и общий чат.
 *
 * <p>Сессию можно открыть для всех — тогда она видна в списке — или оставить
 * закрытой: у закрытой есть только код, и код здесь и есть приглашение.
 *
 * <p>Экран сам обновляет список раз в несколько секунд, а чат получает через
 * удержанный запрос: сообщение приходит сразу, а не по таймеру.
 */
public class BoziLobbyActivity extends Activity {
    private static final long REFRESH_MS = 4000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout playersBox;
    private LinearLayout sessionsBox;
    private TextView statusLine;

    private static final int REQUEST_VPN = 7001;

    private volatile boolean running;
    private String myCode = "";
    private TextView tunnelLine;
    private LinearLayout tunnelBox;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BoziConfig.signedIn(this)) {
            BoziAuthActivity.signOut(this);
            return;
        }
        build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        new Thread(this::pollLobby, "bozi-lobby").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Оба потока держат сетевые запросы; флаг проверяется после каждого,
        // поэтому экран за собой прибирает, а не продолжает опрашивать сервер
        // из фона.
        running = false;
    }

    private void build() {
        LinearLayout root = BoziUi.screenWithTabs(this, BoziTabs.SESSIONS, R.drawable.bozi_bg_sessions);
        BoziUi.title(this, root, "Игроки и сессии");
        statusLine = BoziUi.label(this, root, "Соединяемся…", BoziUi.MUTED);

        BoziUi.button(this, root, "Создать сессию", true, v -> askCreate());
        BoziUi.button(this, root, "Войти по коду", false, v -> askJoin());

        BoziUi.sectionTitle(this, root, "Ваша сессия");
        tunnelBox = BoziUi.card(this, root);
        tunnelLine = BoziUi.label(this, tunnelBox, "Вы пока не в сессии.", BoziUi.MUTED);
        BoziUi.button(this, tunnelBox, "Выйти из сессии", false, v -> leaveSession());

        BoziUi.sectionTitle(this, root, "Открытые сессии");
        sessionsBox = new LinearLayout(this);
        sessionsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(sessionsBox, BoziUi.params(this, 0, 8));

        BoziUi.sectionTitle(this, root, "Игроки в сети");
        playersBox = new LinearLayout(this);
        playersBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(playersBox, BoziUi.params(this, 0, 8));

    }

    // --- опрос сервера ---

    private void pollLobby() {
        while (running) {
            try {
                BoziApi api = new BoziApi(this);
                BoziApi.Lobby lobby = api.lobby();
                BoziTunnel.Status tunnel = BoziTunnel.get().status();
                ui.post(() -> {
                    showLobby(lobby);
                    showTunnel(tunnel);
                });
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(text));
            }
            try {
                Thread.sleep(REFRESH_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    // --- сессии ---

    private void askCreate() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = BoziUi.dp(this, 20);
        box.setPadding(pad, pad, pad, 0);

        EditText title = new EditText(this);
        title.setHint("Название сессии");
        title.setText("Игра " + BoziConfig.login(this));
        box.addView(title);

        CheckBox open = new CheckBox(this);
        open.setText("Показывать всем в списке");
        open.setChecked(true);
        box.addView(open);

        new AlertDialog.Builder(this)
                .setTitle("Новая сессия")
                .setView(box)
                .setPositiveButton("Создать", (d, w) ->
                        create(title.getText().toString().trim(), open.isChecked()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void create(String title, boolean open) {
        statusLine.setText("Создаём сессию…");
        new Thread(() -> {
            try {
                String code = BoziTunnel.get().hostGame(this, BoziConfig.login(this),
                        "zerohour", title, open);
                myCode = code;
                ui.post(() -> {
                    statusLine.setText("Сессия создана. Код: " + code);
                    showCode(code, open);
                    startTunnel();
                });
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(text));
            }
        }, "bozi-create").start();
    }

    private void askJoin() {
        EditText code = new EditText(this);
        code.setHint("Код сессии, например GEN-4F7K");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        new AlertDialog.Builder(this)
                .setTitle("Вход по коду")
                .setView(code)
                .setPositiveButton("Войти", (d, w) -> join(code.getText().toString().trim()))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void join(String code) {
        if (code.isEmpty()) return;
        statusLine.setText("Входим в сессию…");
        new Thread(() -> {
            try {
                BoziTunnel.get().joinGame(this, code, BoziConfig.login(this));
                myCode = BoziTunnel.get().status().code;
                ui.post(() -> {
                    statusLine.setText("Вы в сессии " + myCode);
                    startTunnel();
                });
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(text));
            }
        }, "bozi-join").start();
    }

    // --- туннель ---

    /**
     * Просит разрешение на туннель и поднимает его.
     *
     * <p>Разрешение на VPN система спрашивает у самого игрока, обойти диалог
     * нельзя. Если разрешение уже давали, prepare() вернёт null и сервис
     * запускается сразу.
     */
    private void startTunnel() {
        Intent consent = VpnService.prepare(this);
        if (consent != null) {
            startActivityForResult(consent, REQUEST_VPN);
            return;
        }
        BoziVpnService.start(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN) {
            if (resultCode == RESULT_OK) {
                BoziVpnService.start(this);
            } else {
                statusLine.setText("Без разрешения на туннель игра по сети не заработает");
            }
        }
    }

    private void leaveSession() {
        BoziVpnService.stop(this);
        BoziTunnel.get().stop();
        myCode = "";
        tunnelLine.setText("Вы пока не в сессии.");
        statusLine.setText("Вы вышли из сессии");
    }

    /** Показывает, как идёт связь с каждым соперником. */
    private void showLobby(BoziApi.Lobby lobby) {
        statusLine.setText("В сети " + lobby.online + " · в бою " + lobby.playing);

        sessionsBox.removeAllViews();
        if (lobby.sessions.isEmpty()) {
            BoziUi.label(this, sessionsBox, "Открытых сессий нет. Создайте свою — её увидят все.",
                    BoziUi.MUTED);
        } else {
            for (BoziApi.Session session : lobby.sessions) {
                LinearLayout card = BoziUi.card(this, sessionsBox);

                LinearLayout head = BoziUi.row(this, card);
                TextView name = new TextView(this);
                name.setText(session.title.isEmpty() ? session.code : session.title);
                name.setTextColor(BoziUi.TEXT);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                head.addView(name, BoziUi.grow());
                boolean playing = "playing".equals(session.state);
                BoziUi.pill(this, head, playing ? "идёт бой" : "идёт сбор",
                        playing ? BoziUi.ACCENT : BoziUi.OK);

                // Занятые места показываем точками: «3 из 8» приходится
                // читать, а заполненность ряда видно сразу.
                BoziUi.label(this, card, "хост " + session.host + " · "
                        + pips(session.players, session.maxPlayers)
                        + "  " + session.players + " из " + session.maxPlayers, BoziUi.MUTED);

                if (!playing && session.players < session.maxPlayers) {
                    BoziUi.button(this, card, "Присоединиться", false, v -> join(session.code));
                }
            }
        }

        playersBox.removeAllViews();
        if (lobby.players.isEmpty()) {
            BoziUi.label(this, playersBox, "Сейчас никого нет.", BoziUi.MUTED);
        } else {
            for (BoziApi.Player player : lobby.players) {
                boolean me = player.login.equalsIgnoreCase(lobby.me);
                LinearLayout row = BoziUi.row(this, playersBox);
                BoziUi.avatar(this, row, initials(player.login),
                        me ? BoziUi.ACCENT : BoziUi.MUTED);
                TextView label = new TextView(this);
                label.setText(player.login);
                label.setTextColor(me ? BoziUi.ACCENT : BoziUi.TEXT);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                row.addView(label, BoziUi.grow());
                if (!player.inGame.isEmpty()) {
                    BoziUi.pill(this, row, "в игре " + player.inGame, BoziUi.ACCENT);
                }
            }
        }
    }

    /** Ряд точек: занятые места закрашены, свободные пустые. */
    private static String pips(int taken, int total) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.max(0, total); i++) {
            out.append(i < taken ? '●' : '○');
        }
        return out.toString();
    }

    private static String initials(String login) {
        if (login == null || login.isEmpty()) return "?";
        return login.substring(0, Math.min(2, login.length())).toUpperCase();
    }

    private void showTunnel(BoziTunnel.Status status) {
        if (!status.hasRoom()) {
            tunnelLine.setText(status.error.isEmpty() ? "Вы пока не в сессии." : status.error);
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("Код ").append(status.code);
        if (!status.vip.isEmpty()) text.append(" · ваш адрес ").append(status.vip);
        text.append(status.host ? " · вы хозяин" : " · вы гость");
        text.append(BoziTunnel.get().tunnelStarted() ? "\nТуннель включён" : "\nТуннель ещё не включён");
        for (BoziTunnel.Peer peer : status.peers) {
            text.append("\n").append(peer.nick).append(" — ");
            text.append(peer.online ? peer.path : "не в сети");
            String latency = peer.latencyText();
            if (!latency.isEmpty()) text.append(", ").append(latency);
        }
        tunnelLine.setText(text.toString());
    }

    private void showCode(String code, boolean open) {
        String note = open
                ? "Сессия видна всем в списке. Код тоже работает."
                : "Сессия закрытая: войти можно только по коду.";
        new AlertDialog.Builder(this)
                .setTitle("Код сессии: " + code)
                .setMessage(note + "\n\nПередайте код тому, кого зовёте играть.")
                .setPositiveButton("Понятно", null)
                .show();
    }
}
