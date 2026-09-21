package com.generalsx.zerohour;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Разговоры: общий чат, чат своей сессии и личные переписки.
 *
 * <p>Раньше чат был один на всех и жил полоской внизу лобби. Но разговор
 * перед боем и разговор со всей платформой — разные вещи: в первом
 * договариваются о картах и сторонах, второй нужен, чтобы вообще найти, с кем
 * играть. А позвать конкретного человека, не крича на весь сервер, было нечем
 * вовсе.
 *
 * <p>Ленты переключаются наверху, и у каждой свой счётчик прочитанного:
 * переход между ними не теряет сообщения и не показывает их заново.
 */
public class BoziChatActivity extends Activity {

    /** Куда пишем: общий чат, чат сессии или личная переписка. */
    private enum Feed { GLOBAL, ROOM, DIRECT }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private LinearLayout tabsRow;
    private LinearLayout messagesBox;
    private ScrollView messagesScroll;
    private EditText input;
    private TextView statusLine;
    private TextView emptyNote;

    private volatile boolean running;
    /** Свой счётчик на каждую ленту: иначе при переключении всё дублируется. */
    private volatile long sinceGlobal;
    private volatile long sinceRoom;
    private volatile long sinceDirect;

    private volatile Feed feed = Feed.GLOBAL;
    private volatile String peer = "";
    private String me = "";
    /** Кому можно написать лично: список берём из лобби. */
    private final List<String> online = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BoziConfig.signedIn(this)) {
            BoziAuthActivity.signOut(this);
            return;
        }
        me = BoziConfig.login(this);
        build();
    }

    @Override
    public void onBackPressed() {
        // Разделы — соседи, а не вложенные экраны: «назад» ведёт
        // к первому разделу, а не закрывает приложение.
        if (!BoziTabs.goBack(this, BoziTabs.CHAT)) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        new Thread(this::pollChat, "bozi-chat").start();
        new Thread(this::pollOnline, "bozi-chat-online").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        running = false;
    }

    private void build() {
        LinearLayout root = BoziUi.screenWithTabsFixed(this, BoziTabs.CHAT, R.drawable.bozi_bg_chat);

        LinearLayout head = BoziUi.header(this, root, "соединяемся…", "Чат",
                initials(me), v -> startActivity(new Intent(this, BoziMoreActivity.class)));
        statusLine = (TextView) head.getChildAt(0);

        tabsRow = BoziUi.row(this, root);
        showFeedTabs();

        messagesScroll = new ScrollView(this);
        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        messagesScroll.addView(messagesBox);
        // Лента забирает всю оставшуюся высоту, поле ввода остаётся внизу.
        root.addView(messagesScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout sendRow = BoziUi.row(this, root);
        input = new EditText(this);
        input.setHint("Сообщение");
        input.setHintTextColor(BoziUi.MUTED);
        input.setTextColor(BoziUi.TEXT);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setSingleLine(true);
        // Отправка прямо с клавиатуры: тянуться к кнопке после каждой реплики
        // неудобно, а в чате реплики короткие и частые.
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendChat();
                return true;
            }
            return false;
        });
        GradientDrawable inputBg = new GradientDrawable();
        inputBg.setColor(BoziUi.CARD);
        inputBg.setCornerRadius(BoziUi.dp(this, 12));
        inputBg.setStroke(BoziUi.dp(this, 1), BoziUi.LINE);
        input.setBackground(inputBg);
        int pad = BoziUi.dp(this, 12);
        input.setPadding(pad, pad, pad, pad);
        sendRow.addView(input, BoziUi.grow());

        TextView send = new TextView(this);
        send.setText("Отправить");
        send.setTextColor(BoziUi.ACCENT);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        send.setPadding(BoziUi.dp(this, 14), pad, BoziUi.dp(this, 4), pad);
        send.setOnClickListener(v -> sendChat());
        sendRow.addView(send);

        resetFeed();
    }

    /** Переключатель лент: общий · сессия · личное. */
    private void showFeedTabs() {
        tabsRow.removeAllViews();
        String code = BoziTunnel.get().status().code;

        addFeedTab("Общий", feed == Feed.GLOBAL, v -> switchTo(Feed.GLOBAL, ""));
        if (!code.isEmpty()) {
            addFeedTab("Сессия", feed == Feed.ROOM, v -> switchTo(Feed.ROOM, ""));
        }
        String label = feed == Feed.DIRECT && !peer.isEmpty() ? peer : "Личное";
        addFeedTab(label, feed == Feed.DIRECT, v -> pickPeer());
    }

    private void addFeedTab(String label, boolean active, View.OnClickListener click) {
        TextView chip = BoziUi.languageChip(this, label, active);
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = BoziUi.dp(this, 8);
        tabsRow.addView(chip, lp);
    }

    /** Выбор собеседника: показываем тех, кто сейчас в сети. */
    private void pickPeer() {
        if (online.isEmpty()) {
            statusLine.setText("некому писать: кроме вас никого нет в сети");
            return;
        }
        String[] names = online.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle("Кому написать")
                .setItems(names, (d, which) -> switchTo(Feed.DIRECT, names[which]))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void switchTo(Feed target, String withWhom) {
        feed = target;
        peer = withWhom;
        // Счётчик ленты сбрасываем: при возврате в неё сервер отдаст всё, что
        // там есть, и переписка не выглядит начатой с середины.
        switch (feed) {
            case GLOBAL: sinceGlobal = 0; break;
            case ROOM: sinceRoom = 0; break;
            case DIRECT: sinceDirect = 0; break;
        }
        showFeedTabs();
        resetFeed();
    }

    /** Чистит ленту и ставит подсказку под текущий разговор. */
    private void resetFeed() {
        messagesBox.removeAllViews();
        String hint;
        switch (feed) {
            case ROOM:
                hint = "Чат вашей сессии. Здесь только те, кто в ней, — "
                        + "удобно договориться о картах до начала боя.";
                break;
            case DIRECT:
                hint = peer.isEmpty()
                        ? "Выберите, кому написать."
                        : "Личная переписка с " + peer + ". Видите только вы двое.";
                break;
            default:
                hint = "Общий чат платформы. Сообщение увидят все, кто в сети.";
        }
        emptyNote = BoziUi.label(this, messagesBox, hint, BoziUi.MUTED);
    }

    // --- обмен с сервером ---

    private void pollChat() {
        while (running) {
            Feed current = feed;
            String withWhom = peer;
            try {
                BoziApi api = new BoziApi(this);
                BoziApi.ChatPage page;
                switch (current) {
                    case ROOM: {
                        String code = BoziTunnel.get().status().code;
                        if (code.isEmpty()) {
                            sleep(2000);
                            continue;
                        }
                        page = api.chat(code, sinceRoom);
                        sinceRoom = page.last;
                        break;
                    }
                    case DIRECT: {
                        if (withWhom.isEmpty()) {
                            sleep(1500);
                            continue;
                        }
                        page = api.direct(withWhom, sinceDirect);
                        sinceDirect = page.last;
                        break;
                    }
                    default: {
                        page = api.chat("", sinceGlobal);
                        sinceGlobal = page.last;
                    }
                }
                // Пока ждали ответ, игрок мог переключить ленту — тогда эти
                // сообщения не от неё, и показывать их нельзя.
                if (!page.messages.isEmpty() && current == feed && withWhom.equals(peer)) {
                    ui.post(() -> showMessages(page));
                }
            } catch (Exception e) {
                String text = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(text));
                sleep(3000);
            }
        }
    }

    /** Подпись над чатом и список тех, кому можно написать лично. */
    private void pollOnline() {
        while (running) {
            try {
                BoziApi.Lobby lobby = new BoziApi(this).lobby();
                ui.post(() -> {
                    statusLine.setText("в сети " + lobby.online + " · в бою " + lobby.playing);
                    online.clear();
                    for (BoziApi.Player player : lobby.players) {
                        if (!player.login.equalsIgnoreCase(me)) {
                            online.add(player.login);
                        }
                    }
                    // Сессия могла появиться или закрыться — вкладка «Сессия»
                    // должна появляться и исчезать вместе с ней.
                    showFeedTabs();
                });
            } catch (Exception ignored) {
                // Молча: чат работает и без этой строки.
            }
            sleep(8000);
        }
    }

    private void showMessages(BoziApi.ChatPage page) {
        if (emptyNote != null) {
            messagesBox.removeView(emptyNote);
            emptyNote = null;
        }
        for (BoziApi.ChatMessage message : page.messages) {
            messagesBox.addView(bubble(message, message.from.equalsIgnoreCase(me)));
        }
        messagesScroll.post(() -> messagesScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    /** Пузырь сообщения: свои справа и цветом, чужие слева. */
    private LinearLayout bubble(BoziApi.ChatMessage message, boolean mine) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(mine ? Gravity.END : Gravity.START);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(mine ? BoziUi.withAlpha(BoziUi.ACCENT, 33) : BoziUi.CARD);
        bg.setCornerRadius(BoziUi.dp(this, 12));
        bg.setStroke(BoziUi.dp(this, 1),
                mine ? BoziUi.withAlpha(BoziUi.ACCENT, 56) : BoziUi.LINE);
        box.setBackground(bg);
        int pad = BoziUi.dp(this, 10);
        box.setPadding(pad, BoziUi.dp(this, 7), pad, BoziUi.dp(this, 7));

        TextView head = new TextView(this);
        head.setText((mine ? "Вы" : message.from) + " · " + time(message.at));
        head.setTextColor(BoziUi.MUTED);
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        box.addView(head);

        TextView text = new TextView(this);
        text.setText(message.text);
        text.setTextColor(BoziUi.TEXT);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        box.addView(text);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = BoziUi.dp(this, 4);
        lp.bottomMargin = BoziUi.dp(this, 4);
        line.addView(box, lp);
        return line;
    }

    /**
     * Время сообщения.
     *
     * <p>Сервер шлёт секунды, а не миллисекунды: умножаем здесь, иначе все
     * сообщения оказались бы отправленными в январе 1970 года.
     */
    private String time(long atSeconds) {
        if (atSeconds <= 0) return "";
        return clock.format(new Date(atSeconds * 1000L));
    }

    private void sendChat() {
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (feed == Feed.DIRECT && peer.isEmpty()) {
            pickPeer();
            return;
        }
        input.setText("");
        final Feed current = feed;
        final String withWhom = peer;
        new Thread(() -> {
            try {
                BoziApi api = new BoziApi(this);
                switch (current) {
                    case ROOM:
                        api.say(BoziTunnel.get().status().code, text);
                        break;
                    case DIRECT:
                        api.sayDirect(withWhom, text);
                        break;
                    default:
                        api.say("", text);
                }
            } catch (Exception e) {
                String message = BoziAuthActivity.message(e);
                // Текст возвращаем в поле: иначе написанное пропадает из-за
                // одного обрыва связи, и это злит сильнее самой ошибки.
                ui.post(() -> {
                    statusLine.setText(message);
                    input.setText(text);
                });
            }
        }, "bozi-say").start();
    }

    private static String initials(String login) {
        if (login == null || login.isEmpty()) return "?";
        return login.substring(0, Math.min(2, login.length())).toUpperCase();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            running = false;
        }
    }
}
