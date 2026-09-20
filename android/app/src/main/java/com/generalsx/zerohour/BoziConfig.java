package com.generalsx.zerohour;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Настройки платформы BOZI на стороне телефона.
 *
 * <p>Сервер у платформы один и известен заранее, поэтому адрес зашит в код, а
 * не спрашивается у игрока. Возможность переопределить его оставлена на случай
 * переезда: тогда достаточно записать новые значения в настройки, не выпуская
 * новую сборку.
 */
public final class BoziConfig {
    public static final String PREFS = "bozi";

    public static final String DEFAULT_HOST = "194.124.250.108";
    public static final int DEFAULT_API_PORT = 7443;
    /** Порт ретранслятора: через него идут пакеты игры, когда прямой путь закрыт. */
    public static final int DEFAULT_RELAY_PORT = 7777;

    /**
     * Отпечаток сертификата сервера.
     *
     * <p>У сервера нет доменного имени, поэтому обычная проверка цепочки
     * удостоверяющих центров неприменима. Закрепление отпечатка строже такой
     * проверки: подходит ровно один сертификат — наш. Если сертификат на
     * сервере пересоздадут, приложение перестанет подключаться, пока отпечаток
     * не обновят здесь и не пересоберут APK. Это намеренно: молча доверять
     * новому сертификату значит согласиться на подмену.
     */
    public static final String DEFAULT_FINGERPRINT =
            "4C:DD:E4:A2:FF:27:00:FF:A2:39:9A:2E:FD:9B:FA:7A:B2:CE:42:3E:04:CC:E8:D0:5A:41:AF:04:C6:81:D7:62";

    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_RELAY_PORT = "relayPort";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_TOKEN = "token";
    private static final String KEY_LOGIN = "login";

    private BoziConfig() {}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String host(Context context) {
        return prefs(context).getString(KEY_HOST, DEFAULT_HOST);
    }

    public static int port(Context context) {
        return prefs(context).getInt(KEY_PORT, DEFAULT_API_PORT);
    }

    public static int relayPort(Context context) {
        return prefs(context).getInt(KEY_RELAY_PORT, DEFAULT_RELAY_PORT);
    }

    public static String fingerprint(Context context) {
        return prefs(context).getString(KEY_FINGERPRINT, DEFAULT_FINGERPRINT);
    }

    // --- учётная запись ---

    /** Хранится только выданный сервером токен: пароль в приложении не остаётся. */
    public static String token(Context context) {
        return prefs(context).getString(KEY_TOKEN, "");
    }

    public static String login(Context context) {
        return prefs(context).getString(KEY_LOGIN, "");
    }

    public static boolean signedIn(Context context) {
        return !token(context).isEmpty();
    }

    public static void saveSession(Context context, String login, String token) {
        prefs(context).edit().putString(KEY_LOGIN, login).putString(KEY_TOKEN, token).apply();
    }

    public static void forgetSession(Context context) {
        prefs(context).edit().remove(KEY_TOKEN).apply();
    }
}
