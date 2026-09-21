package com.generalsx.zerohour;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Профиль и всё служебное: учётная запись, язык, разделы «Ещё».
 *
 * <p>До редизайна это жило вперемешку с каталогом игр на главном экране —
 * кнопка выхода из учётной записи соседствовала со списком сборок. Здесь у
 * служебного свой раздел, и главный экран остался про игры.
 *
 * <p>Админка показывается только администратору: обычному игроку строка с
 * чужими возможностями ничего не объясняет, только мешает.
 */
public class BoziMoreActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout statsRow;
    private LinearLayout adminBox;
    private TextView serverLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!BoziConfig.signedIn(this)) {
            BoziAuthActivity.signOut(this);
            return;
        }
        build();
        loadProfile();
    }

    private void build() {
        LinearLayout root = BoziUi.screenWithTabs(this, BoziTabs.MORE, R.drawable.bozi_bg_more);
        BoziUi.header(this, root, "учётная запись", BoziConfig.login(this),
                initials(BoziConfig.login(this)), null);

        LinearLayout account = BoziUi.card(this, root);
        statsRow = BoziUi.row(this, account);
        BoziUi.tile(this, statsRow, "—", "партий");
        BoziUi.tile(this, statsRow, "—", "в игре");
        BoziUi.tile(this, statsRow, daysLeft() + "", "дней токена");

        BoziUi.eyebrow(this, root, "язык приложения").setLayoutParams(BoziUi.params(this, 20, 10));
        LinearLayout languages = new LinearLayout(this);
        languages.setOrientation(LinearLayout.VERTICAL);
        root.addView(languages, BoziUi.params(this, 0, 8));
        showLanguages(languages);

        BoziUi.eyebrow(this, root, "ещё").setLayoutParams(BoziUi.params(this, 20, 10));
        BoziUi.listRow(this, root, "Настройки движка и графики", "разрешение, кадры, DXVK",
                BoziUi.TEXT, v -> startActivity(new Intent(this, SetupActivity.class)));
        BoziUi.listRow(this, root, "Соединение", "туннель, путь до соперников, проверки",
                BoziUi.TEXT, v -> startActivity(new Intent(this, BoziNetworkActivity.class)));
        BoziUi.listRow(this, root, "Журнал ошибок", "отправить лог, если игра упала",
                BoziUi.TEXT, v -> startActivity(new Intent(this, LogViewerActivity.class)));

        adminBox = new LinearLayout(this);
        adminBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(adminBox, BoziUi.params(this, 0, 0));

        BoziUi.listRow(this, root, "Выйти из учётной записи", "токен будет удалён с телефона",
                BoziUi.BAD, v -> BoziAuthActivity.signOut(this));

        serverLine = BoziUi.label(this, root,
                "BOZI " + versionName() + " · " + BoziConfig.host(this), BoziUi.MUTED);
        serverLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
    }

    /**
     * Языки плиткой по два в ряд.
     *
     * <p>Их четырнадцать, и списком в полную ширину они занимали пол-экрана
     * прокрутки — ради настройки, которую меняют один раз в жизни.
     */
    private void showLanguages(LinearLayout parent) {
        String current = LocaleHelper.getSavedLanguageTag(this);
        LinearLayout row = null;
        int inRow = 0;
        for (String tag : LocaleHelper.SUPPORTED_TAGS) {
            if (inRow == 0) {
                row = BoziUi.row(this, parent);
            }
            boolean active = tag.equals(current);
            TextView chip = BoziUi.languageChip(this, LocaleHelper.displayNameFor(this, tag), active);
            chip.setOnClickListener(v -> {
                LocaleHelper.setSavedLanguageTag(this, tag);
                // Перезапускаем экран: подписи уже нарисованы, и менять их по
                // одной — тот же перезапуск, только руками.
                recreate();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = inRow == 0 ? BoziUi.dp(this, 8) : 0;
            row.addView(chip, lp);
            inRow = (inRow + 1) % 2;
        }
        if (inRow == 1) {
            // Нечётный последний ряд: добиваем пустотой, иначе чип растянется
            // во всю ширину и будет выглядеть выбранным.
            View filler = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(filler, lp);
        }
    }

    /**
     * Сколько дней ещё живёт токен.
     *
     * <p>Срок зашит в сам токен: {@code логин.времяИстечения.подпись}. Спрашивать
     * его у сервера незачем — ответ уже лежит на телефоне.
     */
    private long daysLeft() {
        String token = BoziConfig.token(this);
        String[] parts = token.split("\\.");
        if (parts.length < 2) return 0;
        try {
            long expires = Long.parseLong(parts[1]);
            long left = expires - System.currentTimeMillis() / 1000L;
            return Math.max(0, left / 86400L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Статистика игрока и признак администратора приходят с сервера. */
    private void loadProfile() {
        new Thread(() -> {
            try {
                BoziApi.Profile profile = new BoziApi(this).profile();
                ui.post(() -> showProfile(profile));
            } catch (Exception ignored) {
                // Профиль — украшение экрана: без него остальное работает.
            }
        }, "bozi-profile").start();
    }

    private void showProfile(BoziApi.Profile profile) {
        statsRow.removeAllViews();
        BoziUi.tile(this, statsRow, String.valueOf(profile.games), "партий");
        BoziUi.tile(this, statsRow, hours(profile.playedSeconds), "в игре");
        BoziUi.tile(this, statsRow, daysLeft() + "", "дней токена");

        adminBox.removeAllViews();
        if (profile.admin) {
            BoziUi.listRow(this, adminBox, "Админка сервера", "каталог, учётные записи, сессии",
                    BoziUi.ACCENT, v -> startActivity(new Intent(this, BoziAdminActivity.class)));
        }
    }

    /** «41 ч» вместо «147 600 с»: часы — та единица, в которой это читают. */
    private static String hours(long seconds) {
        if (seconds < 3600) return Math.max(0, seconds / 60) + " м";
        return (seconds / 3600) + " ч";
    }

    private static String initials(String login) {
        if (login == null || login.isEmpty()) return "?";
        String trimmed = login.trim();
        return trimmed.substring(0, Math.min(2, trimmed.length())).toUpperCase();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
