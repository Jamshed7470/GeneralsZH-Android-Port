package com.generalsx.zerohour;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Общий чат платформы — свой раздел, а не полоска внизу лобби.
 *
 * <p>Раньше чат жил на экране сессий и занимал там последнюю треть: читать
 * переписку приходилось через окошко в двести точек высотой, и при появлении
 * клавиатуры от него не оставалось ничего. Здесь он занимает экран целиком.
 *
 * <p>Сообщения приходят удержанным запросом (сервер отвечает, когда есть что
 * сказать), поэтому чужая реплика появляется сразу, а не по таймеру.
 */
public class BoziChatActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private LinearLayout messagesBox;
    private ScrollView messagesScroll;
    private EditText input;
    private TextView statusLine;
    /** Подсказка в пустом чате: убирается с первым сообщением. */
    private TextView emptyNote;

    private volatile boolean running;
    private volatile long since;
    private String me = "";

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
                initials(me), v -> startActivity(new android.content.Intent(
                        this, BoziMoreActivity.class)));
        statusLine = (TextView) head.getChildAt(0);

        // Переписка лежит прямо на фоне раздела, без рамки: карточка вокруг
        // ленты сообщений только сужала бы её и спорила с пузырями.
        messagesScroll = new ScrollView(this);
        messagesBox = new LinearLayout(this);
        messagesBox.setOrientation(LinearLayout.VERTICAL);
        messagesScroll.addView(messagesBox);
        emptyNote = BoziUi.label(this, messagesBox,
                "Пока тихо. Напишите первым — сообщение увидят все, кто в сети.", BoziUi.MUTED);
        // Лента забирает всю оставшуюся высоту, поле ввода остаётся внизу.
        root.addView(messagesScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout sendRow = BoziUi.row(this, root);
        input = new EditText(this);
        input.setHint("Сообщение");
        input.setHintTextColor(BoziUi.MUTED);
        input.setTextColor(BoziUi.TEXT);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
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
    }

    private void pollChat() {
        while (running) {
            try {
                BoziApi.ChatPage page = new BoziApi(this).chat("", since);
                since = page.last;
                if (!page.messages.isEmpty()) {
                    ui.post(() -> showMessages(page));
                }
            } catch (Exception e) {
                // Сеть моргнула — подождём и попробуем снова. Ошибку на экран
                // не выносим: чат не должен кричать из-за одного обрыва.
                sleep(3000);
            }
        }
    }

    /** Подпись над чатом: сколько людей сейчас в сети. */
    private void pollOnline() {
        while (running) {
            try {
                BoziApi.Lobby lobby = new BoziApi(this).lobby();
                ui.post(() -> statusLine.setText(
                        "в сети " + lobby.online + " · в бою " + lobby.playing));
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
            boolean mine = message.from.equalsIgnoreCase(me);
            messagesBox.addView(bubble(message, mine));
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
        bg.setColor(mine ? BoziUi.withAlpha(BoziUi.ACCENT, 33) : BoziUi.LINE);
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
        text.setTextColor(mine ? BoziUi.TEXT : BoziUi.TEXT);
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
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        new Thread(() -> {
            try {
                new BoziApi(this).say("", text);
            } catch (Exception e) {
                String message = BoziAuthActivity.message(e);
                ui.post(() -> statusLine.setText(message));
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
