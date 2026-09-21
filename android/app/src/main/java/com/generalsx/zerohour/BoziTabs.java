package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Нижняя панель переходов: Игры · Сессии · Чат · Ещё.
 *
 * <p>До редизайна экраны открывались друг из друга кнопками, и вернуться с
 * третьего экрана к первому можно было только «назад» дважды. Панель делает
 * переходы плоскими: из любого места один тап до любого раздела.
 *
 * <p>Размеры и поведение взяты из прототипа: иконка в подсвеченной плашке
 * 34×26, под ней подпись капсом с разрядкой, у панели — верхняя линия и
 * затемнение к низу, чтобы содержимое уезжало под неё не резко.
 *
 * <p>Каждый раздел — отдельный Activity: у приложения уже такая сборка
 * экранов, и переводить её на фрагменты ради панели значит трогать всё
 * разом. Переходы идут с {@code CLEAR_TOP | SINGLE_TOP}, поэтому стек
 * остаётся плоским, как и вид.
 */
final class BoziTabs {

    static final String LIBRARY = "library";
    static final String SESSIONS = "sessions";
    static final String CHAT = "chat";
    static final String MORE = "more";

    private BoziTabs() {}

    /**
     * Куда ведёт «назад» из раздела.
     *
     * <p>Разделы — соседи, а не вложенные экраны, поэтому системная кнопка
     * «назад» закрывала приложение прямо из чата. Ожидание другое: вернуться
     * к первому разделу, и только из него выйти. Возвращает true, если
     * переход сделан.
     */
    static boolean goBack(Activity a, String from) {
        if (LIBRARY.equals(from)) {
            return false;
        }
        Intent intent = new Intent(a, BoziHomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        a.startActivity(intent);
        a.overridePendingTransition(0, 0);
        a.finish();
        return true;
    }

    /** Дорисовывает панель в низ экрана и подсвечивает текущий раздел. */
    static void attach(Activity a, LinearLayout root, String active) {
        View line = new View(a);
        line.setBackgroundColor(BoziUi.withAlpha(Color.WHITE, 18));
        root.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, BoziUi.dp(a, 1) / 2)));

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { BoziUi.withAlpha(BoziUi.DEEP, 210), BoziUi.DEEP });
        bar.setBackground(bg);
        final int padTop = BoziUi.dp(a, 9);
        final int padSide = BoziUi.dp(a, 12);
        final int padBottom = BoziUi.dp(a, 10);
        bar.setPadding(padSide, padTop, padSide, padBottom);

        tab(a, bar, LIBRARY, "Игры", R.drawable.ic_tab_library, active, BoziHomeActivity.class);
        tab(a, bar, SESSIONS, "Сессии", R.drawable.ic_tab_sessions, active, BoziLobbyActivity.class);
        tab(a, bar, CHAT, "Чат", R.drawable.ic_tab_chat, active, BoziChatActivity.class);
        tab(a, bar, MORE, "Ещё", R.drawable.ic_tab_more, active, BoziMoreActivity.class);

        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Панель системной навигации перекрыла бы нижний ряд: высоту
        // спрашиваем у системы, потому что с жестами она одна, с тремя
        // кнопками другая, и зашитое число ошибётся на одном из них.
        bar.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(padSide, padTop, padSide, padBottom + bottom);
            return insets;
        });
    }

    private static void tab(Activity a, LinearLayout bar, String id, String label, int icon,
                            String active, Class<?> screen) {
        boolean current = id.equals(active);
        int fg = current ? BoziUi.ACCENT : BoziUi.MUTED;

        LinearLayout item = new LinearLayout(a);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setPadding(0, BoziUi.dp(a, 9), 0, BoziUi.dp(a, 4));

        // Плашка под иконкой: у текущего раздела подсвечена, у остальных
        // прозрачная — место под неё занято всегда, иначе подписи прыгали бы.
        LinearLayout pill = new LinearLayout(a);
        pill.setGravity(Gravity.CENTER);
        if (current) {
            GradientDrawable pillBg = new GradientDrawable();
            pillBg.setColor(BoziUi.withAlpha(BoziUi.ACCENT, 36));
            pillBg.setCornerRadius(BoziUi.dp(a, 9));
            pill.setBackground(pillBg);
        }
        ImageView glyph = new ImageView(a);
        glyph.setImageResource(icon);
        glyph.setColorFilter(fg);
        pill.addView(glyph, new LinearLayout.LayoutParams(BoziUi.dp(a, 19), BoziUi.dp(a, 19)));
        item.addView(pill, new LinearLayout.LayoutParams(BoziUi.dp(a, 34), BoziUi.dp(a, 26)));

        TextView text = new TextView(a);
        text.setText(label);
        text.setTextColor(fg);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        text.setAllCaps(true);
        text.setLetterSpacing(0.12f);
        text.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = BoziUi.dp(a, 6);
        item.addView(text, textLp);

        if (!current) {
            item.setOnClickListener(v -> {
                Intent intent = new Intent(a, screen);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                a.startActivity(intent);
                // Без анимации: переключение разделов должно выглядеть сменой
                // содержимого на месте, а не переходом вглубь.
                a.overridePendingTransition(0, 0);
            });
        }

        bar.addView(item, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }
}
