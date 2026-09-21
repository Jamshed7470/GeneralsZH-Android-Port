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

    /** Экран без нижней панели, но со снимком раздела: вход и подобные. */
    static LinearLayout screen(Activity a, int background) {
        LinearLayout holder = new LinearLayout(a);
        holder.setOrientation(LinearLayout.VERTICAL);
        LinearLayout column = fillColumn(a, holder, true);
        a.setContentView(background == 0 ? holder : withBackdrop(a, holder, background));
        return column;
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

    /** Знак платформы во всю ширину столбца. */
    static android.widget.ImageView logo(Activity a, LinearLayout parent, int drawable) {
        android.widget.ImageView view = new android.widget.ImageView(a);
        view.setImageResource(drawable);
        view.setAdjustViewBounds(true);
        view.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 150));
        lp.topMargin = dp(a, 12);
        lp.bottomMargin = dp(a, 8);
        parent.addView(view, lp);
        return view;
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

    // --- блоки редизайна ---
    //
    // Ниже то, из чего собраны новые экраны. Всё так же кодом и в одном месте:
    // разнести половину в XML значит сверять два источника правды при каждой
    // правке отступа.

    /**
     * Экран с нижней панелью переходов.
     *
     * <p>Отличается от {@link #screen} тем, что панель не уезжает вместе с
     * содержимым: прокручивается только столбец над ней. Поэтому корень —
     * вертикальный столбец, а не ScrollView.
     */
    static LinearLayout screenWithTabs(Activity a, String activeTab) {
        return screenWithTabs(a, activeTab, 0);
    }

    /**
     * То же, но с фоновым снимком раздела.
     *
     * <p>У каждого раздела своя картинка — так, войдя в приложение, человек
     * узнаёт экран раньше, чем прочтёт заголовок. Поверх картинки лежит
     * затемняющая вуаль: без неё белый текст на светлых местах снимка
     * перестаёт читаться, а с ней фон остаётся фоном и не спорит с
     * содержимым.
     */
    static LinearLayout screenWithTabs(Activity a, String activeTab, int background) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Нижний отступ здесь не нужен: под столбцом стоит панель
        // переходов, и системный отступ учитывает она.
        LinearLayout column = fillColumn(a, root, false);
        BoziTabs.attach(a, root, activeTab);
        a.setContentView(background == 0 ? root : withBackdrop(a, root, background));
        return column;
    }

    /**
     * Прокручиваемый столбец во всю оставшуюся высоту родителя.
     *
     * <p>Нижний отступ берём у системы: с жестами панель навигации одна
     * высоты, с тремя кнопками другая, и зашитое число перекроет содержимое
     * на одном из них.
     */
    private static LinearLayout fillColumn(Activity a, LinearLayout parent, boolean bottomInset) {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(a, 20);
        final int top = dp(a, 28);
        column.setPadding(pad, top, pad, pad);
        if (bottomInset) {
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
        }
        scroll.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return column;
    }

    /**
     * Кладёт содержимое поверх снимка раздела и вуали.
     *
     * <p>Вуаль — вертикальный переход от полупрозрачного к сплошному фону:
     * вверху снимок видно, к низу он растворяется. Так заголовок лежит на
     * картинке, а списки и кнопки — на ровном тёмном поле, где им и место.
     */
    private static android.widget.FrameLayout withBackdrop(Activity a, LinearLayout content,
                                                           int background) {
        android.widget.FrameLayout stack = new android.widget.FrameLayout(a);
        stack.setBackgroundColor(BG);

        android.widget.ImageView image = new android.widget.ImageView(a);
        image.setImageResource(background);
        image.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        stack.addView(image, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View veil = new View(a);
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] { withAlpha(BG, 120), withAlpha(BG, 215), BG });
        veil.setBackground(gradient);
        stack.addView(veil, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        content.setBackgroundColor(Color.TRANSPARENT);
        stack.addView(content, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return stack;
    }

    /** Плитка со значением и подписью: «128 / партий». */
    static LinearLayout tile(Activity a, LinearLayout row, String value, String label) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(a, 12));
        bg.setStroke(dp(a, 1), LINE);
        box.setBackground(bg);
        int pad = dp(a, 12);
        box.setPadding(pad, pad, pad, pad);

        TextView top = new TextView(a);
        top.setText(value);
        top.setTextColor(TEXT);
        top.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        box.addView(top);

        TextView bottom = new TextView(a);
        bottom.setText(label);
        bottom.setTextColor(MUTED);
        bottom.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        box.addView(bottom);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(a, 8);
        row.addView(box, lp);
        return box;
    }

    /** Пилюля состояния: короткое слово в цветной рамке. */
    static TextView pill(Activity a, LinearLayout row, String text, int color) {
        TextView view = new TextView(a);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        view.setAllCaps(true);
        view.setLetterSpacing(0.06f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(withAlpha(color, 26));
        bg.setCornerRadius(dp(a, 20));
        bg.setStroke(dp(a, 1), withAlpha(color, 90));
        view.setBackground(bg);
        view.setPadding(dp(a, 10), dp(a, 4), dp(a, 10), dp(a, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(a, 6);
        row.addView(view, lp);
        return view;
    }

    /** Кружок с инициалами вместо аватара: фотографий у нас нет. */
    static TextView avatar(Activity a, LinearLayout row, String initials, int color) {
        TextView view = new TextView(a);
        view.setText(initials);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        view.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(withAlpha(color, 26));
        bg.setStroke(dp(a, 1), withAlpha(color, 90));
        view.setBackground(bg);
        int size = dp(a, 38);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.rightMargin = dp(a, 12);
        row.addView(view, lp);
        return view;
    }

    /** Полоса выполнения: прямоугольник в прямоугольнике, без виджета. */
    static View progress(Activity a, LinearLayout parent, int percent) {
        LinearLayout track = new LinearLayout(a);
        GradientDrawable trackBg = new GradientDrawable();
        trackBg.setColor(Color.parseColor("#1b222a"));
        trackBg.setCornerRadius(dp(a, 6));
        track.setBackground(trackBg);

        View fill = new View(a);
        GradientDrawable fillBg = new GradientDrawable();
        fillBg.setColor(ACCENT);
        fillBg.setCornerRadius(dp(a, 6));
        fill.setBackground(fillBg);
        track.addView(fill, new LinearLayout.LayoutParams(0, dp(a, 8),
                Math.max(1, Math.min(100, percent))));
        View rest = new View(a);
        track.addView(rest, new LinearLayout.LayoutParams(0, dp(a, 8),
                Math.max(0, 100 - Math.max(1, Math.min(100, percent)))));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(a, 8));
        lp.topMargin = dp(a, 8);
        lp.bottomMargin = dp(a, 8);
        parent.addView(track, lp);
        return track;
    }

    /** Строка списка: название, пояснение и стрелка вправо. */
    static LinearLayout listRow(Activity a, LinearLayout parent, String label, String hint,
                                int labelColor, View.OnClickListener onClick) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(a, 12));
        bg.setStroke(dp(a, 1), LINE);
        box.setBackground(bg);
        box.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        box.setClickable(onClick != null);
        box.setOnClickListener(onClick);

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(a);
        title.setText(label);
        title.setTextColor(labelColor);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        texts.addView(title);
        if (hint != null && !hint.isEmpty()) {
            TextView sub = new TextView(a);
            sub.setText(hint);
            sub.setTextColor(MUTED);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            texts.addView(sub);
        }
        box.addView(texts, grow());

        if (onClick != null) {
            TextView arrow = new TextView(a);
            arrow.setText("›");
            arrow.setTextColor(MUTED);
            arrow.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            box.addView(arrow);
        }
        parent.addView(box, params(a, 0, 8));
        return box;
    }

    /** Переключатель: строка с пояснением и кружком, который ездит. */
    static LinearLayout toggleRow(Activity a, LinearLayout parent, String label, String hint,
                                  boolean on, View.OnClickListener onClick) {
        LinearLayout box = listRow(a, parent, label, hint, TEXT, onClick);
        // Стрелку из listRow тут не рисуем: у переключателя свой индикатор.
        View knobTrack = new View(a);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? withAlpha(ACCENT, 66) : Color.parseColor("#1b222a"));
        bg.setCornerRadius(dp(a, 12));
        bg.setStroke(dp(a, 1), on ? withAlpha(ACCENT, 128) : LINE);
        knobTrack.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(a, 42), dp(a, 24));
        box.addView(knobTrack, lp);

        TextView mark = new TextView(a);
        mark.setText(on ? "вкл" : "выкл");
        mark.setTextColor(on ? ACCENT : MUTED);
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        LinearLayout.LayoutParams markLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        markLp.leftMargin = dp(a, 8);
        box.addView(mark, markLp);
        return box;
    }

    /** Цвет с другой прозрачностью: 0–255. */
    static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }
}
