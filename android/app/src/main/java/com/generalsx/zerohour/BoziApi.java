package com.generalsx.zerohour;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Связь с сервером платформы: учётная запись, каталог игр, их загрузка,
 * лобби и чат.
 *
 * <p>Все методы синхронные и рассчитаны на вызов из фонового потока: сетевые
 * запросы в главном потоке Android запрещает сам. Ошибки приходят исключением
 * с русским текстом от сервера — показывать его игроку можно как есть.
 */
public final class BoziApi {
    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 40_000;
    /** Длинный опрос чата держится на сервере до 25 секунд, ждём с запасом. */
    private static final int POLL_TIMEOUT_MS = 40_000;

    private final String base;
    private final SSLSocketFactory socketFactory;
    private final Context context;

    public BoziApi(Context context) throws IOException {
        this.context = context.getApplicationContext();
        this.base = "https://" + BoziConfig.host(context) + ":" + BoziConfig.port(context);
        this.socketFactory = pinnedFactory(BoziConfig.fingerprint(context));
    }

    /** Ошибка с текстом, который не стыдно показать игроку. */
    public static final class ApiException extends IOException {
        public final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // --- защищённое соединение ---

    private static SSLSocketFactory pinnedFactory(String fingerprint) throws IOException {
        final byte[] expected = parseFingerprint(fingerprint);
        try {
            TrustManager[] managers = {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("сервер не прислал сертификат");
                    }
                    try {
                        byte[] got = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                        if (!MessageDigest.isEqual(got, expected)) {
                            throw new CertificateException(
                                    "отпечаток сертификата не совпал — соединение прервано");
                        }
                    } catch (java.security.NoSuchAlgorithmException e) {
                        throw new CertificateException(e);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, managers, null);
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IOException("не настроить защищённое соединение", e);
        }
    }

    private static byte[] parseFingerprint(String value) throws IOException {
        String clean = value.replace(":", "").replace(" ", "").replace("-", "").trim();
        if (clean.length() != 64) {
            throw new IOException("отпечаток сертификата задан неверно");
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            out[i] = (byte) Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private HttpURLConnection open(String path, int readTimeout) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        if (conn instanceof HttpsURLConnection) {
            ((HttpsURLConnection) conn).setSSLSocketFactory(socketFactory);
            // Имя в сертификате не проверяем: у сервера только адрес, а
            // подлинность уже доказана совпадением отпечатка.
            ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
        }
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(readTimeout);
        String token = BoziConfig.token(context);
        if (!token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        return conn;
    }

    private String request(String method, String path, JSONObject body, int readTimeout)
            throws IOException {
        HttpURLConnection conn = open(path, readTimeout);
        try {
            conn.setRequestMethod(method);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes("UTF-8"));
                }
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readAll(stream);
            if (status >= 400) {
                throw new ApiException(status, errorText(text, status));
            }
            return text;
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(0, "нет связи с сервером");
        } finally {
            conn.disconnect();
        }
    }

    private JSONObject requestObject(String method, String path, JSONObject body) throws IOException {
        return parseObject(request(method, path, body, READ_TIMEOUT_MS));
    }

    private static JSONObject parseObject(String text) throws IOException {
        try {
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } catch (Exception e) {
            throw new ApiException(0, "сервер ответил непонятное");
        }
    }

    private static String errorText(String body, int status) {
        try {
            String message = new JSONObject(body).optString("error", "");
            if (!message.isEmpty()) return message;
        } catch (Exception ignored) {
        }
        return "сервер ответил " + status;
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) > 0) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    private static JSONObject json(String... pairs) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < pairs.length; i += 2) {
                o.put(pairs[i], pairs[i + 1]);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    // --- учётная запись ---

    /** Регистрация. Возвращает токен и сразу его сохраняет. */
    public void register(String login, String password) throws IOException {
        JSONObject res = requestObject("POST", "/v1/auth/register", json("login", login, "password", password));
        String token = res.optString("token", "");
        if (token.isEmpty()) throw new ApiException(0, "сервер не выдал токен");
        BoziConfig.saveSession(context, login, token);
    }

    public void login(String login, String password) throws IOException {
        JSONObject res = requestObject("POST", "/v1/auth/login", json("login", login, "password", password));
        String token = res.optString("token", "");
        if (token.isEmpty()) throw new ApiException(0, "сервер не выдал токен");
        BoziConfig.saveSession(context, login, token);
    }

    /** Проверка сохранённого токена: не спрашивать пароль при каждом запуске. */
    public String whoami() throws IOException {
        return requestObject("GET", "/v1/auth/whoami", null).optString("login", "");
    }

    // --- каталог ---

    /** Одна игра каталога. */
    public static final class Game {
        public String id = "";
        public String title = "";
        public String subtitle = "";
        public String base = "";
        public long sizeBytes;
        public long unpackedMb;
        public boolean ready;
        public boolean addon;
        /**
         * Нужны ли файлы базовой Generals. Признак приходит с сервера, а не
         * угадывается по названию: там, где лежат сами файлы, известно, чем
         * сборка является.
         */
        public boolean needsBase;
        /** Версия служебного файла (для движка — versionCode APK). */
        public long version;

        public String sizeText() {
            if (sizeBytes <= 0) return "";
            return Math.round(sizeBytes / 1048576.0) + " МБ";
        }
    }

    public List<Game> catalog() throws IOException {
        JSONObject res = requestObject("GET", "/v1/catalog", null);
        List<Game> out = new ArrayList<>();
        collect(out, res.optJSONArray("builds"), false);
        collect(out, res.optJSONArray("addons"), true);
        List<Game> extras = new ArrayList<>();
        collect(extras, res.optJSONArray("extras"), false);
        engine = null;
        for (Game g : extras) if (BoziPcGames.ENGINE_ID.equals(g.id)) engine = g;
        return out;
    }

    /** Движок компьютерных игр из последнего ответа каталога; null — не выложен. */
    public Game engine;

    private static void collect(List<Game> out, JSONArray array, boolean addon) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            if (o == null) continue;
            Game g = new Game();
            g.id = o.optString("id", "");
            g.title = o.optString("title", g.id);
            g.subtitle = o.optString("subtitle", "");
            g.base = o.optString("base", "");
            g.sizeBytes = o.optLong("sizeBytes", 0);
            g.unpackedMb = o.optLong("unpackedMb", 0);
            g.ready = o.optBoolean("ready", false);
            g.needsBase = o.optBoolean("needsBase", false);
            g.version = o.optLong("version", 0);
            g.addon = addon;
            out.add(g);
        }
    }

    public interface ProgressListener {
        /** @return false, чтобы прервать загрузку */
        boolean onProgress(long done, long total);
    }

    /**
     * Скачивает игру с докачкой: если часть файла уже есть, догружается
     * остаток. На мобильном интернете полгигабайта с первого раза доходят
     * редко, а начинать заново каждый раз — верный способ не докачать никогда.
     *
     * @return контрольная сумма, объявленная сервером (может быть пустой)
     */
    public String download(String id, File dest, ProgressListener listener) throws IOException {
        long have = dest.exists() ? dest.length() : 0;
        HttpURLConnection conn = open("/v1/files/" + id, READ_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        if (have > 0) {
            conn.setRequestProperty("Range", "bytes=" + have + "-");
        }
        try {
            int status = conn.getResponseCode();
            if (status == 401) throw new ApiException(status, "нужно войти заново");
            if (status == 404) throw new ApiException(status, "этой игры больше нет на сервере");
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) {
                throw new ApiException(status, "сервер ответил " + status);
            }
            boolean resuming = status == HttpURLConnection.HTTP_PARTIAL;
            if (!resuming && have > 0) {
                // Сервер не поддержал докачку — качаем заново, иначе получим
                // склейку двух кусков и битый архив.
                have = 0;
            }
            long total = have + Math.max(conn.getContentLength(), 0);
            String declaredSum = conn.getHeaderField("X-GL-SHA256");

            try (InputStream in = new BufferedInputStream(conn.getInputStream(), 1 << 16);
                 FileOutputStream out = new FileOutputStream(dest, resuming)) {
                byte[] buf = new byte[1 << 16];
                long done = have;
                long lastTick = 0;
                int n;
                // Обрыв посреди чтения — обычное дело на мобильной сети. Наружу
                // он уходит своим текстом («unexpected end of stream» и прочие
                // сообщения системной библиотеки игроку ничего не говорят), а
                // докачивать умеет вызывающий: файл остаётся на месте.
                while ((n = readOrExplain(in, buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    // Обновлять полоску на каждый блок незачем: 64 КБ на
                    // быстром канале — это сотни раз в секунду.
                    if (listener != null && System.currentTimeMillis() - lastTick > 250) {
                        lastTick = System.currentTimeMillis();
                        if (!listener.onProgress(done, total)) {
                            throw new IOException("загрузка отменена");
                        }
                    }
                }
                if (listener != null) listener.onProgress(done, total);
            }
            return declaredSum == null ? "" : declaredSum;
        } finally {
            conn.disconnect();
        }
    }

    /** Чтение с переводом сетевой ошибки на человеческий язык. */
    private static int readOrExplain(InputStream in, byte[] buf) throws IOException {
        try {
            return in.read(buf);
        } catch (IOException e) {
            throw new ApiException(0, "связь оборвалась — продолжим с этого места");
        }
    }

    // --- лобби ---

    /** Игрок в лобби. */
    public static final class Player {
        public String login = "";
        public boolean online;
        public String inGame = "";
    }

    /** Открытая игра в лобби. */
    public static final class Session {
        public String code = "";
        public String title = "";
        public String game = "";
        public String host = "";
        public int players;
        public int maxPlayers;
        public boolean open;
        public String state = "";
    }

    /** Состояние лобби: кто в сети и какие игры собираются. */
    public static final class Lobby {
        public String me = "";
        public int online;
        public int playing;
        public final List<Player> players = new ArrayList<>();
        public final List<Session> sessions = new ArrayList<>();
    }

    /** Карточка игрока: сколько сыграно и есть ли доступ к админке. */
    public static final class Profile {
        public String login = "";
        public boolean admin;
        public int games;
        public long playedSeconds;
        public long createdAt;
    }

    public Profile profile() throws IOException {
        JSONObject res = parseObject(request("GET", "/v1/profile", null, READ_TIMEOUT_MS));
        Profile profile = new Profile();
        profile.login = res.optString("login", "");
        profile.admin = res.optBoolean("admin", false);
        profile.games = res.optInt("games", 0);
        profile.playedSeconds = res.optLong("playedSeconds", 0);
        profile.createdAt = res.optLong("createdAt", 0);
        return profile;
    }

    public Lobby lobby() throws IOException {
        JSONObject res = requestObject("GET", "/v1/lobby", null);
        Lobby out = new Lobby();
        out.me = res.optString("me", "");
        out.online = res.optInt("online", 0);
        out.playing = res.optInt("playing", 0);
        JSONArray players = res.optJSONArray("players");
        if (players != null) {
            for (int i = 0; i < players.length(); i++) {
                JSONObject o = players.optJSONObject(i);
                if (o == null) continue;
                Player p = new Player();
                p.login = o.optString("login", "");
                p.online = o.optBoolean("online", false);
                p.inGame = o.optString("inGame", "");
                out.players.add(p);
            }
        }
        JSONArray rooms = res.optJSONArray("rooms");
        if (rooms != null) {
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject o = rooms.optJSONObject(i);
                if (o == null) continue;
                out.sessions.add(session(o));
            }
        }
        return out;
    }

    /** Разбор одной сессии: один и тот же вид в лобби и в админке. */
    private static Session session(JSONObject o) {
        Session s = new Session();
        s.code = o.optString("code", "");
        s.title = o.optString("title", "");
        s.game = o.optString("game", "");
        s.host = o.optString("host", "");
        s.players = o.optInt("players", 0);
        s.maxPlayers = o.optInt("maxPlayers", 8);
        s.open = o.optBoolean("public", false);
        s.state = o.optString("state", "lobby");
        return s;
    }

    // --- админка ---

    /** Учётная запись глазами админки. */
    public static final class Account {
        public String login = "";
        public boolean admin;
        public boolean banned;
        public boolean online;
        public int games;
        public long playedSeconds;
    }

    /** Состояние сервера целиком: то же, что показывает веб-админка. */
    public static final class Overview {
        public int users;
        public int online;
        public int rooms;
        public int playing;
        public long uptimeSeconds;
        public final List<Session> roomList = new ArrayList<>();
        public final List<Account> accounts = new ArrayList<>();
        public final List<Game> catalog = new ArrayList<>();
    }

    public Overview adminOverview() throws IOException {
        JSONObject res = requestObject("GET", "/v1/admin/overview", null);
        Overview out = new Overview();
        out.users = res.optInt("users", 0);
        out.online = res.optInt("online", 0);
        out.rooms = res.optInt("rooms", 0);
        out.playing = res.optInt("playing", 0);
        out.uptimeSeconds = res.optLong("uptimeSeconds", 0);

        JSONArray rooms = res.optJSONArray("roomList");
        if (rooms != null) {
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject o = rooms.optJSONObject(i);
                if (o != null) out.roomList.add(session(o));
            }
        }
        JSONArray accounts = res.optJSONArray("accounts");
        if (accounts != null) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject o = accounts.optJSONObject(i);
                if (o == null) continue;
                Account a = new Account();
                a.login = o.optString("login", "");
                a.admin = o.optBoolean("admin", false);
                a.banned = o.optBoolean("banned", false);
                a.online = o.optBoolean("online", false);
                a.games = o.optInt("games", 0);
                a.playedSeconds = o.optLong("playedSeconds", 0);
                out.accounts.add(a);
            }
        }
        collect(out.catalog, res.optJSONArray("catalog"), false);
        return out;
    }

    /** Действия над игроком: ban, unban, admin, unadmin, delete. */
    public void adminUser(String login, String action) throws IOException {
        requestObject("POST", "/v1/admin/user", json("login", login, "action", action));
    }

    public void adminCloseRoom(String code) throws IOException {
        requestObject("POST", "/v1/admin/room", json("code", code, "action", "close"));
    }

    // --- чат ---

    public static final class ChatMessage {
        public long id;
        public String from = "";
        public String text = "";
        public long at;
    }

    public static final class ChatPage {
        public long last;
        public final List<ChatMessage> messages = new ArrayList<>();
    }

    /**
     * Читает чат, начиная с указанного сообщения. Сервер держит соединение до
     * появления нового, поэтому вызов возвращается либо с сообщениями, либо
     * через 25 секунд — опрашивать по таймеру не нужно.
     */
    /** Личная переписка: та же лента, только адрес другой. */
    public ChatPage direct(String login, long since) throws IOException {
        return page("/v1/dm/" + login + "?since=" + since, since);
    }

    public void sayDirect(String login, String text) throws IOException {
        requestObject("POST", "/v1/dm/" + login, json("text", text));
    }

    public ChatPage chat(String roomCode, long since) throws IOException {
        String path = roomCode == null || roomCode.isEmpty()
                ? "/v1/lobby/chat?since=" + since
                : "/v1/rooms/" + roomCode + "/chat?since=" + since;
        return page(path, since);
    }

    private ChatPage page(String path, long since) throws IOException {
        JSONObject res = parseObject(request("GET", path, null, POLL_TIMEOUT_MS));
        ChatPage page = new ChatPage();
        page.last = res.optLong("last", since);
        JSONArray arr = res.optJSONArray("messages");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                ChatMessage m = new ChatMessage();
                m.id = o.optLong("id", 0);
                m.from = o.optString("from", "");
                m.text = o.optString("text", "");
                m.at = o.optLong("at", 0);
                page.messages.add(m);
            }
        }
        return page;
    }

    public void say(String roomCode, String text) throws IOException {
        String path = roomCode == null || roomCode.isEmpty()
                ? "/v1/lobby/chat"
                : "/v1/rooms/" + roomCode + "/chat";
        requestObject("POST", path, json("text", text));
    }

    /**
     * Закрыть свою сессию по учётной записи, без секрета участника.
     *
     * <p>После перезапуска приложения секрет участника пропадает вместе с
     * памятью, а комната на сервере остаётся. Хозяину нужен способ убрать
     * её — по логину, которым она создана.
     */
    public void closeRoom(String code) throws IOException {
        requestObject("POST", "/v1/rooms/" + code + "/close", json());
    }

    // --- игровые сессии ---

    /** Членство в сессии: свой адрес в туннеле и секрет для связи с сервером. */
    public static final class Membership {
        public String code = "";
        public String token = "";
        public String vip = "";
        public int netmask = 24;
        public long relayId;
        public int mtu;
        public boolean host;
    }

    private static Membership membership(JSONObject res) {
        Membership m = new Membership();
        m.code = res.optString("code", "");
        m.token = res.optString("token", "");
        m.vip = res.optString("vip", "");
        m.netmask = res.optInt("netmask", 24);
        m.relayId = res.optLong("relayId", 0);
        m.mtu = res.optInt("mtu", 1280);
        m.host = res.optBoolean("isHost", false);
        return m;
    }

    /**
     * Создаёт сессию. Публичная попадает в общий список, закрытая живёт только
     * по коду — код и есть приглашение.
     */
    public Membership createSession(String gameId, String hostKey, String nick, String title, boolean open)
            throws IOException {
        JSONObject body = json("hostKey", hostKey, "nick", nick, "game", gameId, "title", title);
        try {
            body.put("public", open);
        } catch (Exception ignored) {
        }
        return membership(requestObject("POST", "/v1/rooms", body));
    }

    /**
     * Сообщает серверу, что партия началась или вернулась к сбору.
     *
     * <p>Отсюда лобби знает, к кому ещё можно присоединиться, а к кому поздно,
     * и по этому же признаку админка считает, сколько игроков сейчас в бою.
     */
    public void setSessionState(String code, String state) throws IOException {
        requestObject("POST", "/v1/rooms/" + code + "/status", json("state", state));
    }

    public Membership joinSession(String code, String key, String nick) throws IOException {
        return membership(requestObject("POST", "/v1/rooms/" + code + "/join", json("key", key, "nick", nick)));
    }
}
