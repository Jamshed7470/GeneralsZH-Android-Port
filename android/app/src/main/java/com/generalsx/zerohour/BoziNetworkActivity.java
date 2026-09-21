package com.generalsx.zerohour;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/**
 * Соединение: каким путём идёт игра и всё ли для неё подготовлено.
 *
 * <p>Раньше про туннель говорила одна строка в лобби: «через сервер, 275 мс».
 * Когда игра не находила соперника, этого не хватало, чтобы понять, где
 * оборвалось, — и разбираться приходилось через adb. Здесь всё, что решает
 * вопрос «почему не видно друг друга»: путь до каждого соперника, его
 * задержка и список подготовок, которые приложение делает перед запуском.
 *
 * <p>Ничего не придумываем: показывается то, что сетевая часть знает на самом
 * деле. Если данных нет — так и написано, а не ноль, который выглядит как
 * измерение.
 */
public class BoziNetworkActivity extends Activity {

    private static final long REFRESH_MS = 2000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView routeLine;
    private TextView stateLine;
    private LinearLayout peersBox;
    private LinearLayout checksBox;
    private LinearLayout togglesBox;

    private volatile boolean running;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        build();
    }

    @Override
    protected void onStart() {
        super.onStart();
        running = true;
        new Thread(this::poll, "bozi-net").start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        running = false;
    }

    private void build() {
        LinearLayout root = BoziUi.screen(this, R.drawable.bozi_bg_network);
        BoziUi.title(this, root, "Соединение");
        stateLine = BoziUi.label(this, root, "Смотрим состояние туннеля…", BoziUi.MUTED);

        LinearLayout card = BoziUi.card(this, root);
        routeLine = BoziUi.label(this, card, "Туннель выключен", BoziUi.TEXT);
        routeLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);

        BoziUi.sectionTitle(this, root, "Соперники");
        peersBox = new LinearLayout(this);
        peersBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(peersBox, BoziUi.params(this, 0, 8));

        BoziUi.sectionTitle(this, root, "Что подготовлено");
        checksBox = new LinearLayout(this);
        checksBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(checksBox, BoziUi.params(this, 0, 8));

        BoziUi.sectionTitle(this, root, "Настройки сети");
        togglesBox = new LinearLayout(this);
        togglesBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(togglesBox, BoziUi.params(this, 0, 8));
        showToggles();
    }

    private void poll() {
        while (running) {
            BoziTunnel.Status status = BoziTunnel.get().status();
            ui.post(() -> show(status));
            try {
                Thread.sleep(REFRESH_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void show(BoziTunnel.Status status) {
        if (!status.hasRoom()) {
            stateLine.setText("Вы не в сессии — туннеля нет.");
            routeLine.setText("Туннель выключен");
            peersBox.removeAllViews();
            BoziUi.label(this, peersBox, "Войдите в сессию, и здесь появятся соперники.",
                    BoziUi.MUTED);
            showChecks(status);
            return;
        }

        stateLine.setText("Сессия " + status.code + " · вы "
                + (status.host ? "хозяин" : "гость"));
        routeLine.setText(status.connected()
                ? "Ваш адрес " + status.vip
                : "Туннель поднимается…");

        peersBox.removeAllViews();
        if (status.peers.isEmpty()) {
            BoziUi.label(this, peersBox, "Пока вы один в сессии.", BoziUi.MUTED);
        } else {
            for (BoziTunnel.Peer peer : status.peers) {
                LinearLayout card = BoziUi.card(this, peersBox);
                LinearLayout head = BoziUi.row(this, card);
                BoziUi.avatar(this, head, initials(peer.nick),
                        peer.online ? BoziUi.OK : BoziUi.MUTED);

                LinearLayout names = new LinearLayout(this);
                names.setOrientation(LinearLayout.VERTICAL);
                TextView nick = new TextView(this);
                nick.setText(peer.nick + (peer.host ? " · хозяин" : ""));
                nick.setTextColor(BoziUi.TEXT);
                nick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                names.addView(nick);
                TextView route = new TextView(this);
                route.setText(peer.vip + " · " + pathWords(peer.path)
                        + (peer.latencyText().isEmpty() ? "" : " · " + peer.latencyText()));
                route.setTextColor(BoziUi.MUTED);
                route.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                names.addView(route);
                head.addView(names, BoziUi.grow());

                BoziUi.pill(this, head, peer.online ? "на связи" : "молчит",
                        peer.online ? BoziUi.OK : BoziUi.MUTED);
            }
        }
        showChecks(status);
    }

    /**
     * Проверки, которые обычно и объясняют «не видим друг друга».
     *
     * <p>Каждая строка — не обещание, а факт с устройства: файл есть или нет,
     * ключ записан или нет. Поэтому здесь читается настоящий Options.ini, а
     * не запоминается, что приложение собиралось его записать.
     */
    private void showChecks(BoziTunnel.Status status) {
        checksBox.removeAllViews();

        File data = zhDataDir();
        String pinned = readIpAddress(new File(data, "Options.ini"));
        check("Адрес игры записан", pinned.isEmpty()
                ? "ключ IPAddress не задан — игра выберет адрес сама"
                : "IPAddress = " + pinned, !pinned.isEmpty());

        boolean matches = !pinned.isEmpty() && pinned.equals(status.vip);
        if (!pinned.isEmpty()) {
            check("Адрес совпадает с туннелем", matches
                            ? "совпадает с " + status.vip
                            : "в файле " + pinned + ", а туннель выдал "
                              + (status.vip.isEmpty() ? "другой" : status.vip),
                    matches);
        }

        boolean separate = new File(zhRoot(), "Command and Conquer Generals Data").isDirectory()
                && data.isDirectory();
        check("Данные игры и дополнения раздельно", separate
                ? "две папки, как на ПК"
                : "папки ещё не созданы", separate);

        check("Туннель поднят", status.connected()
                ? "WireGuard работает"
                : (status.error.isEmpty() ? "туннель выключен" : status.error),
                status.connected());
    }

    private void check(String label, String hint, boolean good) {
        LinearLayout row = BoziUi.listRow(this, checksBox, label, hint, BoziUi.TEXT, null);
        BoziUi.pill(this, row, good ? "да" : "нет", good ? BoziUi.OK : BoziUi.BAD);
    }

    /**
     * Переключатели сети.
     *
     * <p>«Только через сервер» нужен там, где прямой путь встаёт, но рвётся:
     * на некоторых мобильных сетях соединение живёт минуту и отваливается, и
     * стабильные 200 мс через ретранслятор лучше, чем 40 мс с обрывами.
     */
    private void showToggles() {
        togglesBox.removeAllViews();
        boolean relayOnly = BoziConfig.relayOnly(this);
        BoziUi.toggleRow(this, togglesBox, "Только через сервер",
                "надёжнее, но задержка выше — включайте, если прямой путь рвётся",
                relayOnly, v -> {
                    BoziConfig.setRelayOnly(this, !relayOnly);
                    BoziTunnel.get().applyRelayOnly(this);
                    showToggles();
                });
    }

    // --- чтение с устройства ---

    private File zhRoot() {
        File root = Environment.getExternalStorageDirectory();
        return root == null ? new File("/storage/emulated/0") : new File(root, "Generals");
    }

    private File zhDataDir() {
        File shared = new File(zhRoot(), "Command and Conquer Generals Zero Hour Data");
        if (shared.isDirectory()) return shared;
        File own = getExternalFilesDir(null);
        return new File(own, "Generals/Command and Conquer Generals Zero Hour Data");
    }

    /** Достаёт из Options.ini адрес, который приложение выдало игре. */
    private String readIpAddress(File options) {
        if (!options.isFile()) return "";
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(options), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equals("IPAddress")) {
                    return parts[1].trim();
                }
            }
        } catch (Exception ignored) {
            // Файла нет или не прочитать — это и есть ответ «ключ не задан».
        }
        return "";
    }

    private static String pathWords(String path) {
        if ("direct".equals(path) || "прямой".equals(path)) return "прямое соединение";
        if ("relay".equals(path) || "через сервер".equals(path)) return "через сервер";
        return path.isEmpty() ? "путь неизвестен" : path;
    }

    private static String initials(String nick) {
        if (nick == null || nick.isEmpty()) return "?";
        return nick.substring(0, Math.min(2, nick.length())).toUpperCase();
    }
}
