package com.generalsx.zerohour;

import android.content.Context;
import android.util.Base64;

import java.security.SecureRandom;

/**
 * Ключ телефона в игровой сети платформы.
 *
 * <p>Сессия на сервере заводится под открытым ключом устройства — по нему
 * участники находят друг друга, и по нему же сервер раздаёт адреса внутри
 * игровой подсети. Ключ постоянный: пересоздавать его при каждом запуске
 * значит каждый раз приходить в комнату новым игроком.
 *
 * <p>Здесь он только хранится. Поднятие самого туннеля живёт отдельно и
 * появится следующим шагом — разделение намеренное: список игроков, сессии и
 * чат не должны ждать готовности сетевой части, а сетевой части нужен ровно
 * этот ключ и ничего больше.
 */
final class BoziTunnel {
    private static final String KEY_PUBLIC = "device_key";

    private BoziTunnel() {}

    /** Открытый ключ устройства, 32 байта в base64. */
    static String publicKey(Context context) {
        String saved = BoziConfig.prefs(context).getString(KEY_PUBLIC, "");
        if (!saved.isEmpty()) return saved;

        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String key = Base64.encodeToString(raw, Base64.NO_WRAP);
        BoziConfig.prefs(context).edit().putString(KEY_PUBLIC, key).apply();
        return key;
    }
}
