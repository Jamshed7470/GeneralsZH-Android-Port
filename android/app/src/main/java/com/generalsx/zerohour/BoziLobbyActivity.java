package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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
    private LinearLayout chatBox;
    private ScrollView chatScroll;
    private EditText chatInput;
    private TextView statusLine;

    private volatile boolean running;
    private volatile long chatSince;
    private String myCode = "";

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
        new Thread(this::pollChat, "bozi-chat").start();
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
        LinearLayout root = BoziUi.screen(this);
        BoziUi.title(this, root, "Игроки и сессии");
        statusLine = BoziUi.label(this, root, "Соединяемся…", BoziUi.MUTED);

        BoziUi.button(this, root, "Создать сессию", true, v -> askCreate());
        BoziUi.button(this, root, "Войти по коду", false, v -> askJoin());

        BoziUi.sectionTitle(this, root, "Открытые сессии");
        sessionsBox = new LinearLayout(this);
        sessionsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(sessionsBox, BoziUi.params(this, 0, 8));

        BoziUi.sectionTitle(this, root, "Игроки в сети");
        playersBox = new LinearLayout(this);
        playersBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(playersBox, BoziUi.params(this, 0, 8));

        BoziUi.sectionTitle(this, root, "Общий чат");
        LinearLayout chatCard = BoziUi.card(this, root);
        chatScroll = new ScrollView(this);
        chatBox = new LinearLayout(this);
        chatBox.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(chatBox);
        LinearLayout.LayoutParams chatParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, BoziUi.dp(this, 220));
        chatCard.addView(chatScroll, chatParams);

        LinearLayout sendRow = BoziUi.row(this, chatCard);
        chatInput = new EditText(this);
        chatInput.setHint("Сообщение");
        chatInput.setHintTextColor(BoziUi.MUTED);
        chatInput.setTextColor(BoziUi.TEXT);
        chatInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        sendRow.addView(chatInput, BoziUi.grow());
        TextView send = new TextView(this);
        send.setText("Отправить");
        send.setTextColor(BoziUi.ACCENT);
        send.setPadding(BoziUi.dp(this, 12), 0, 0, 0);
        send.setOnClickListener(v -> sendChat());
        sendRow.addView(send);
    }

    // --- опрос сервера ---

    private void pollLobby() {
        while (running) {
            try {
                BoziApi api = new BoziApi(this);
                BoziApi.Lobby lobby = api.lobby();
                ui.post(() -> showLobby(lobby));
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

    private void pollChat() {
        while (running) {
            try {
                BoziApi api = new BoziApi(this);
                BoziApi.ChatPage page = api.chat(myCode, chatSince);
                chatSince = page.last;
                if (!page.messages.isEmpty()) {
                    ui.post(() -> showMessages(page));
                }
            } catch (Exception e) {
                // Сеть моргнула — ждём и пробуем снова. Ошибку чата на экран
                // не выносим: она перекрыла бы состояние лобби, которое важнее.
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    private void showLobby(BoziApi.Lobby lobby) {
        statusLine.setText("В сети: " + lobby.online + " · в бою: " + lobby.playing);

        sessionsBox.removeAllViews();
        if (lobby.sessions.isEmpty()) {
            BoziUi.label(this, sessionsBox, "Открытых сессий нет. Создайте свою — её увидят все.", BoziUi.MUTED);
        } else {
            for (BoziApi.Session session : lobby.sessions) {
                LinearLayout card = BoziUi.card(this, sessionsBox);
                TextView name = BoziUi.label(this, card, session.title, BoziUi.TEXT);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
                String state = "playing".equals(session.state) ? "идёт бой" : "идёт сбор";
                BoziUi.label(this, card, "Хост: " + session.host + " · игроков "
                        + session.players + " из " + session.maxPlayers + " · " + state, BoziUi.MUTED);
                if (!"playing".equals(session.state) && session.players < session.maxPlayers) {
                    BoziUi.button(this, card, "Присоединиться", false, v -> join(session.code));
                }
            }
        }

        playersBox.removeAllViews();
        if (lobby.players.isEmpty()) {
            BoziUi.label(this, playersBox, "Сейчас никого нет.", BoziUi.MUTED);
        } else {
            for (BoziApi.Player player : lobby.players) {
                String suffix = player.inGame.isEmpty() ? "" : " · в игре " + player.inGame;
                boolean me = player.login.equalsIgnoreCase(lobby.me);
                BoziUi.label(this, playersBox, (me ? "• " : "") + player.login + suffix,
                        me ? BoziUi.ACCENT : BoziUi.TEXT);
            }
        }
    }

    private void showMessages(BoziApi.ChatPage page) {
        for (BoziApi.ChatMessage message : page.messages) {
            TextView line = new TextView(this);
            line.setTextColor(BoziUi.TEXT);
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            line.setText(message.from + ": " + message.text);
            chatBox.addView(line);
        }
        chatScroll.post(() -> chatScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void sendChat() {
        String text = chatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        chatInput.setText("");
        new Thread(() -> {
            try {
                new BoziApi(this).say(myCode, text);
            } catch (Exception e) {
                String message = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(message));
            }
        }, "bozi-say").start();
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
                BoziApi api = new BoziApi(this);
                BoziApi.Membership m = api.createSession("zerohour", BoziTunnel.publicKey(this),
                        BoziConfig.login(this), title, open);
                myCode = m.code;
                chatSince = 0;
                ui.post(() -> {
                    chatBox.removeAllViews();
                    statusLine.setText("Сессия создана. Код: " + m.code);
                    showCode(m.code, open);
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
                BoziApi api = new BoziApi(this);
                BoziApi.Membership m = api.joinSession(code, BoziTunnel.publicKey(this),
                        BoziConfig.login(this));
                myCode = m.code;
                chatSince = 0;
                ui.post(() -> {
                    chatBox.removeAllViews();
                    statusLine.setText("Вы в сессии " + m.code + ", ваш адрес " + m.vip);
                });
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(text));
            }
        }, "bozi-join").start();
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
