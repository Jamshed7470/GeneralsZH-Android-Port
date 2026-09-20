package com.generalsx.zerohour;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Внешний вид BOZI, собранный кодом.
 *
 * <p>Без файлов разметки намеренно: экранов немного, они линейные, и держать
 * их в одном месте с логикой проще, чем сверять три XML с кодом при каждой
 * правке. Цвета те же, что и в админке платформы, чтобы это выглядело одним
 * продуктом, а не двумя разными.
 */
final class BoziUi {
    static final int BG = Color.parseColor("#0f1216");
    static final int CARD = Color.parseColor("#171c22");
    static final int LINE = Color.parseColor("#232a32");
    static final int TEXT = Color.parseColor("#e7ecf2");
    static final int MUTED = Color.parseColor("#8b97a6");
    static final int ACCENT = Color.parseColor("#ffb020");
    static final int OK = Color.parseColor("#35c46a");
    static final int BAD = Color.parseColor("#ef5350");

    private BoziUi() {}

    static int dp(Activity a, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                a.getResources().getDisplayMetrics()));
    }

    /** Экран целиком: прокручиваемый столбец на тёмном фоне. */
    static LinearLayout screen(Activity a) {
        ScrollView scroll = new ScrollView(a);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(a, 20);
        final int top = dp(a, 28);
        column.setPadding(pad, top, pad, pad);

        // Нижнюю кнопку списка перекрывала системная панель навигации: нажатие
        // уходило ей, а не приложению. Спрашиваем у системы реальную высоту
        // панели и добавляем её к отступу — на телефонах с жестами она одна, с
        // тремя кнопками другая, и зашитое число ошибётся на одном из них.
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bottom = insets.getInsets(WindowInsets.Type.systemBars()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            column.setPadding(pad, top, pad, pad + bottom);
            return insets;
        });

        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        a.setContentView(scroll);
        return column;
    }

    static TextView title(Activity a, LinearLayout parent, String text) {
        TextView view = new TextView(a);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        view.setLetterSpacing(0.14f);
        parent.addView(view, params(a, 0, 4));
        return view;
    }

    static TextView label(Activity a, LinearLayout parent, String text, int color) {
        TextView view = new TextView(a);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        parent.addView(view, params(a, 0, 8));
        return view;
    }

    static TextView sectionTitle(Activity a, LinearLayout parent, String text) {
        TextView view = new TextView(a);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        view.setLetterSpacing(0.1f);
        view.setAllCaps(true);
        parent.addView(view, params(a, 20, 8));
        return view;
    }

    /** Карточка: рамка с закруглением, внутрь кладут что угодно. */
    static LinearLayout card(Activity a, LinearLayout parent) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(a, 14));
        bg.setStroke(dp(a, 1), LINE);
        box.setBackground(bg);
        int pad = dp(a, 16);
        box.setPadding(pad, pad, pad, pad);
        parent.addView(box, params(a, 0, 12));
        return box;
    }

    static TextView button(Activity a, LinearLayout parent, String text, boolean primary,
                           View.OnClickListener onClick) {
        TextView view = new TextView(a);
        view.setText(text);
        view.setAllCaps(true);
        view.setGravity(Gravity.CENTER);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTextColor(primary ? Color.parseColor("#1a1206") : TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(primary ? ACCENT : Color.parseColor("#222a33"));
        bg.setCornerRadius(dp(a, 12));
        bg.setStroke(dp(a, 1), primary ? ACCENT : LINE);
        view.setBackground(bg);
        int padV = dp(a, 14);
        view.setPadding(dp(a, 18), padV, dp(a, 18), padV);
        view.setClickable(true);
        view.setOnClickListener(onClick);
        parent.addView(view, params(a, 0, 10));
        return view;
    }

    static EditText input(Activity a, LinearLayout parent, String hint, boolean password) {
        EditText view = new EditText(a);
        view.setHint(hint);
        view.setHintTextColor(MUTED);
        view.setTextColor(TEXT);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        view.setSingleLine(true);
        view.setInputType(password
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#10151a"));
        bg.setCornerRadius(dp(a, 10));
        bg.setStroke(dp(a, 1), LINE);
        view.setBackground(bg);
        int pad = dp(a, 12);
        view.setPadding(pad, pad, pad, pad);
        parent.addView(view, params(a, 0, 10));
        return view;
    }

    /** Строка «слева текст, справа кнопка». */
    static LinearLayout row(Activity a, LinearLayout parent) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(box, params(a, 0, 8));
        return box;
    }

    static LinearLayout.LayoutParams params(Activity a, int topDp, int bottomDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(a, topDp);
        lp.bottomMargin = dp(a, bottomDp);
        return lp;
    }

    /** Растягивающийся элемент строки: занимает всё свободное место. */
    static LinearLayout.LayoutParams grow() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        return lp;
    }
}
