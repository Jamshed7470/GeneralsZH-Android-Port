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
        LinearLayout head = BoziUi.header(this, root, "соединяемся…", "Сессии",
                initials(BoziConfig.login(this)),
                v -> startActivity(new Intent(this, BoziMoreActivity.class)));
        // Надстрочник в шапке — он же строка состояния: «в сети 3 · в бою 1».
        statusLine = (TextView) head.getChildAt(0);

        LinearLayout actions = BoziUi.row(this, root);
        TextView create = BoziUi.chipButton(this, "Создать", true);
        create.setOnClickListener(v -> askCreate());
        actions.addView(create, BoziUi.grow());
        TextView join = BoziUi.chipButton(this, "Войти по коду", false);
        join.setOnClickListener(v -> askJoin());
        LinearLayout.LayoutParams joinLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        joinLp.leftMargin = BoziUi.dp(this, 10);
        actions.addView(join, joinLp);

        // Карточка своей сессии показывается, только когда сессия есть:
        // пустая рамка с кнопкой «выйти» сбивала с толку.
        tunnelBox = new LinearLayout(this);
        tunnelBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(tunnelBox, BoziUi.params(this, 14, 0));

        BoziUi.eyebrow(this, root, "открытые сессии").setLayoutParams(BoziUi.params(this, 20, 10));
        sessionsBox = new LinearLayout(this);
        sessionsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(sessionsBox, BoziUi.params(this, 0, 8));

        BoziUi.eyebrow(this, root, "игроки в сети").setLayoutParams(BoziUi.params(this, 20, 10));
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
        tunnelBox.removeAllViews();
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

    /**
     * Карточка своей сессии: код крупно, рядом соперники и путь до каждого.
     *
     * <p>Код — главное, что отсюда уносят: его диктуют или пересылают. Поэтому
     * он набран крупно и моноширинно (так не путают ноль с буквой), и рядом
     * стоит кнопка копирования.
     */
    private void showTunnel(BoziTunnel.Status status) {
        tunnelBox.removeAllViews();
        if (!status.hasRoom()) {
            if (!status.error.isEmpty()) {
                BoziUi.label(this, tunnelBox, status.error, BoziUi.BAD);
            }
            return;
        }

        LinearLayout card = BoziUi.card(this, tunnelBox);
        BoziUi.eyebrow(this, card, status.host ? "ваша сессия · вы хозяин" : "ваша сессия · вы гость");

        LinearLayout codeRow = BoziUi.row(this, card);
        TextView code = new TextView(this);
        code.setText(status.code);
        code.setTextColor(BoziUi.ACCENT);
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        code.setLetterSpacing(0.08f);
        code.setTypeface(android.graphics.Typeface.MONOSPACE);
        codeRow.addView(code, BoziUi.grow());

        TextView copy = BoziUi.chipButton(this, "Копировать", false);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("BOZI", status.code));
            statusLine.setText("код скопирован");
        });
        codeRow.addView(copy);

        String address = status.vip.isEmpty() ? "адрес выдаётся…" : "ваш адрес " + status.vip;
        BoziUi.label(this, card, address + " · "
                + (BoziTunnel.get().tunnelStarted() ? "туннель включён" : "туннель ещё не включён"),
                BoziUi.MUTED);

        for (BoziTunnel.Peer peer : status.peers) {
            LinearLayout row = BoziUi.row(this, card);
            BoziUi.avatar(this, row, initials(peer.nick), peer.online ? BoziUi.OK : BoziUi.MUTED);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView nick = new TextView(this);
            nick.setText(peer.nick + (peer.host ? " · хозяин" : ""));
            nick.setTextColor(BoziUi.TEXT);
            nick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            texts.addView(nick);
            TextView route = new TextView(this);
            String latency = peer.latencyText();
            route.setText(peer.online
                    ? peer.path + (latency.isEmpty() ? "" : " · " + latency)
                    : "не в сети");
            route.setTextColor(BoziUi.MUTED);
            route.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            texts.addView(route);
            row.addView(texts, BoziUi.grow());
        }

        LinearLayout bottom = BoziUi.row(this, card);
        TextView net = BoziUi.chipButton(this, "Соединение", false);
        net.setOnClickListener(v -> startActivity(new Intent(this, BoziNetworkActivity.class)));
        bottom.addView(net, BoziUi.grow());
        TextView leave = BoziUi.chipButton(this, "Выйти", false);
        leave.setOnClickListener(v -> leaveSession());
        LinearLayout.LayoutParams leaveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        leaveLp.leftMargin = BoziUi.dp(this, 10);
        bottom.addView(leave, leaveLp);
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
