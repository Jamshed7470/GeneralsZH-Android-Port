package com.generalsx.zerohour;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Игровая сеть BOZI: комната, туннель и соперники.
 *
 * <p>Сессия в приложении одна, потому что и туннель на устройстве может быть
 * только один: VpnService не допускает второго. Экран лобби, сервис туннеля и
 * сама игра смотрят в этот объект.
 *
 * <p>Между телефонами поднимается WireGuard. Сначала пробуется прямой путь —
 * телефоны пробивают NAT навстречу друг другу; если провайдер этого не
 * позволяет (CGNAT), связь идёт через наш сервер. Прямой путь заметно быстрее,
 * поэтому он всегда пробуется первым, а ретранслятор остаётся запасным.
 */
public final class BoziTunnel {
    private static BoziTunnel instance;

    private glnet.Session session;
    private String lastError = "";
    private boolean tunnelStarted;

    private BoziTunnel() {}

    public static synchronized BoziTunnel get() {
        if (instance == null) instance = new BoziTunnel();
        return instance;
    }

    synchronized glnet.Session raw() {
        return session;
    }

    synchronized boolean tunnelStarted() {
        return tunnelStarted;
    }

    synchronized void markTunnelStarted(boolean started) {
        tunnelStarted = started;
    }

    synchronized String lastError() {
        return lastError;
    }

    private synchronized glnet.Session ensure(Context context) throws Exception {
        if (session == null) {
            // Токен учётной записи идёт в сетевую часть: комнату на сервере
            // заводит вошедший игрок, и в лобби видно, кто её хозяин.
            session = glnet.Glnet.newAuthorized(
                    BoziConfig.host(context),
                    BoziConfig.port(context),
                    BoziConfig.relayPort(context),
                    BoziConfig.fingerprint(context),
                    BoziConfig.token(context));
        }
        return session;
    }

    /** Создаёт игру. Возвращает код, который зовущий передаёт друзьям. */
    public synchronized String hostGame(Context context, String nick, String game, String title,
                                        boolean open) throws Exception {
        try {
            String code = ensure(context).hostGameNamed(nick, game, title, open);
            lastError = "";
            return code;
        } catch (Exception e) {
            lastError = describe(e);
            throw e;
        }
    }

    /** Входит в чужую игру по коду. */
    public synchronized void joinGame(Context context, String code, String nick) throws Exception {
        try {
            ensure(context).joinGame(code, nick);
            lastError = "";
        } catch (Exception e) {
            lastError = describe(e);
            throw e;
        }
    }

    public synchronized void stop() {
        if (session != null) {
            session.stop();
        }
        tunnelStarted = false;
    }

    /** Ошибки из Go приходят с длинным префиксом — оставляем человеческую часть. */
    private static String describe(Exception e) {
        String text = e.getMessage();
        if (text == null || text.isEmpty()) return "не удалось связаться с сервером";
        int cut = text.lastIndexOf(": ");
        return cut >= 0 && cut + 2 < text.length() ? text.substring(cut + 2) : text;
    }

    // --- состояние для экрана ---

    /** Соперник в комнате. */
    public static final class Peer {
        public String nick = "";
        public String vip = "";
        public String path = "";
        public long rttMs;
        public long rttUs;
        public boolean online;
        public boolean host;

        /**
         * Задержка словами. В одной сети Wi-Fi она меньше миллисекунды, и
         * голый ноль выглядел бы как «не измерено».
         */
        public String latencyText() {
            if (rttUs <= 0) return "";
            return rttMs >= 1 ? rttMs + " мс" : "меньше 1 мс";
        }
    }

    /** Снимок состояния сети. */
    public static final class Status {
        public String state = "idle";
        public String error = "";
        public String code = "";
        public String vip = "";
        public boolean host;
        public long relayRttMs;
        public final List<Peer> peers = new ArrayList<>();

        public boolean connected() {
            return "connected".equals(state);
        }

        public boolean hasRoom() {
            return !code.isEmpty();
        }
    }

    public synchronized Status status() {
        Status out = new Status();
        if (session == null) {
            out.error = lastError;
            return out;
        }
        try {
            JSONObject o = new JSONObject(session.statusJSON());
            out.state = o.optString("state", "idle");
            out.error = o.optString("error", lastError);
            out.code = o.optString("code", "");
            out.vip = o.optString("vip", "");
            out.host = o.optBoolean("isHost", false);
            out.relayRttMs = o.optLong("relayRttMs", 0);
            JSONArray peers = o.optJSONArray("peers");
            if (peers != null) {
                for (int i = 0; i < peers.length(); i++) {
                    JSONObject p = peers.optJSONObject(i);
                    if (p == null) continue;
                    Peer peer = new Peer();
                    peer.nick = p.optString("nick", "");
                    peer.vip = p.optString("vip", "");
                    peer.path = p.optString("path", "");
                    peer.rttMs = p.optLong("rttMs", 0);
                    peer.rttUs = p.optLong("rttUs", 0);
                    peer.online = p.optBoolean("online", false);
                    peer.host = p.optBoolean("isHost", false);
                    out.peers.add(peer);
                }
            }
        } catch (Exception e) {
            out.error = describe(e);
        }
        return out;
    }
}
