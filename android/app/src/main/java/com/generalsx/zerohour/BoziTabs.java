package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Нижняя панель переходов: Игры · Сессии · Чат · Ещё.
 *
 * <p>До редизайна экраны открывались друг из друга кнопками, и вернуться с
 * третьего экрана к первому можно было только через «назад» дважды. Панель
 * делает переходы плоскими: из любого места один тап до любого раздела.
 *
 * <p>Каждый раздел — отдельный Activity, а не фрагмент: у приложения уже такая
 * сборка экранов, и переписывать её ради панели значит трогать всё разом.
 * Чтобы возвращаться не накапливались, переходы идут с флагами
 * {@code CLEAR_TOP | SINGLE_TOP} — стек остаётся плоским, как и вид.
 */
final class BoziTabs {

    static final String LIBRARY = "library";
    static final String SESSIONS = "sessions";
    static final String CHAT = "chat";
    static final String MORE = "more";

    private BoziTabs() {}

    /** Дорисовывает панель в низ экрана и подсвечивает текущий раздел. */
    static void attach(Activity a, LinearLayout root, String active) {
        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(Color.parseColor("#0c1014"));
        int padV = BoziUi.dp(a, 8);
        bar.setPadding(0, padV, 0, padV);

        View line = new View(a);
        line.setBackgroundColor(BoziUi.LINE);
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, BoziUi.dp(a, 1)));

        tab(a, bar, LIBRARY, "Игры", active, BoziHomeActivity.class);
        tab(a, bar, SESSIONS, "Сессии", active, BoziLobbyActivity.class);
        tab(a, bar, CHAT, "Чат", active, BoziChatActivity.class);
        tab(a, bar, MORE, "Ещё", active, BoziMoreActivity.class);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Панель системной навигации перекрывала бы нижний ряд: спрашиваем её
        // высоту у системы, потому что с жестами она одна, с тремя кнопками
        // другая, и зашитое число ошибётся на одном из них.
        bar.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(0, padV, 0, padV + bottom);
            return insets;
        });
    }

    private static void tab(Activity a, LinearLayout bar, String id, String label,
                            String active, Class<?> screen) {
        boolean current = id.equals(active);

        LinearLayout item = new LinearLayout(a);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, BoziUi.dp(a, 6), 0, BoziUi.dp(a, 6));

        if (current) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(BoziUi.withAlpha(BoziUi.ACCENT, 36));
            bg.setCornerRadius(BoziUi.dp(a, 12));
            item.setBackground(bg);
        }

        TextView text = new TextView(a);
        text.setText(label);
        text.setTextColor(current ? BoziUi.ACCENT : BoziUi.MUTED);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        item.addView(text);

        if (!current) {
            item.setOnClickListener(v -> {
                Intent intent = new Intent(a, screen);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                a.startActivity(intent);
                // Без анимации: переключение разделов должно выглядеть как
                // смена содержимого на месте, а не как переход вглубь.
                a.overridePendingTransition(0, 0);
            });
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int side = BoziUi.dp(a, 6);
        lp.leftMargin = side;
        lp.rightMargin = side;
        bar.addView(item, lp);
    }
}
