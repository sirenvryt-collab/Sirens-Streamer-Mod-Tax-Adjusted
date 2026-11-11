package com.donutstreamer.music;

import com.donutstreamer.Actions;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.Locale;

/**
 * YouTube / YouTube Music "now playing" bridge. No account, no Premium, no login.
 * <p>
 * How it works: a small userscript (written to donutstreamer/youtube-music.user.js, install it in Tampermonkey
 * or Violentmonkey) runs on your YouTube tab and sends what is playing to this mod over 127.0.0.1 only. The same
 * script mutes the tab while an ad plays and restores the sound afterwards. Controls (play/pause, next, previous,
 * volume) travel back the same way: the script polls, the mod never reaches into the browser.
 * <p>
 * Security: the server only listens on the loopback address and every request must carry the random token that
 * is baked into your own copy of the userscript. Nothing leaves your PC. The mod plays no audio and never
 * downloads media.
 * <p>
 * A plain ServerSocket is used on purpose (the JDK's built-in HTTP server module is missing from some launcher runtimes).
 */
public final class YtMusic {
    private YtMusic() {}

    private static final Logger LOG = LoggerFactory.getLogger("donutstreamer");
    private static final Gson GSON = new Gson();

    public static final int DEFAULT_PORT = 8765;
    private static final long STALE_MS = 6_000L;       // no report for this long = browser tab gone
    private static final int MAX_BODY = 16 * 1024;

    /** What is playing right now. Progress is extrapolated locally between reports. */
    public record Now(String id, String title, String artist, long durationMs, long progressMs,
                      boolean playing, boolean ad, boolean muted, int volume, String source, long atNanos) {
        public long progressNow() {
            if (!playing || ad) return Math.min(durationMs > 0 ? durationMs : progressMs, progressMs);
            long p = progressMs + (System.nanoTime() - atNanos) / 1_000_000L;
            return durationMs > 0 ? Math.min(durationMs, p) : p;
        }
    }

    // ---- state shared with the HUD (written by server threads, read on the game thread) ----
    private static volatile Now now = null;
    private static volatile long lastReportMs = 0;
    private static volatile String status = "Not set up. Run /ds music setup";
    private static volatile boolean muteAds = true;
    private static volatile boolean running = false;
    private static volatile boolean everConnected = false;

    // ---- server ----
    private static ServerSocket server;
    private static Thread acceptThread;
    private static int port = DEFAULT_PORT;
    private static String token = "";
    private static volatile String activeTab = "";
    private static volatile boolean activePlaying = false;
    private static final ArrayDeque<String> COMMANDS = new ArrayDeque<>();

    // ================= lifecycle =================

    public static void init() {
        load();
        start();
    }

    public static void shutdown() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        server = null;
    }

    private static Path cfgPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutstreamer-music.json");
    }

    public static Path scriptPath() {
        return FabricLoader.getInstance().getGameDir().resolve("donutstreamer").resolve("youtube-music.user.js");
    }

    private static void load() {
        try {
            Path p = cfgPath();
            if (Files.exists(p)) {
                JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
                if (o.has("token")) token = o.get("token").getAsString();
                if (o.has("port")) port = Math.max(1024, Math.min(65535, o.get("port").getAsInt()));
                if (o.has("muteAds")) muteAds = o.get("muteAds").getAsBoolean();
            }
        } catch (Exception e) {
            LOG.warn("Could not read music settings, using defaults", e);
        }
        if (token.length() < 16) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            token = HexFormat.of().formatHex(b);
            save();
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("token", token);
        o.addProperty("port", port);
        o.addProperty("muteAds", muteAds);
        String json = GSON.toJson(o);
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(cfgPath().getParent());
                Files.writeString(cfgPath(), json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("Could not save music settings", e);
            }
        }, "donutstreamer-music-save");
        t.setDaemon(true);
        t.start();
    }

    private static synchronized void start() {
        shutdown();
        try {
            server = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
            running = true;
            status = "Waiting for your YouTube tab (run /ds music setup if you have not installed the script)";
            ServerSocket s = server;
            acceptThread = new Thread(() -> acceptLoop(s), "donutstreamer-music");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException e) {
            running = false;
            status = "Port " + port + " is busy. Pick another with /ds music port <number>";
            LOG.warn("Music bridge could not listen on port {}", port, e);
        }
    }

    // ================= queries (HUD + commands) =================

    public static boolean connected() {
        return running && System.currentTimeMillis() - lastReportMs < STALE_MS;
    }

    /** Current track, or null when no YouTube tab is reporting. */
    public static Now now() {
        return connected() ? now : null;
    }

    public static String statusText() {
        if (!running) return status;
        if (connected()) return "Connected";
        return everConnected ? "YouTube tab not reporting (open or play something)" : status;
    }

    public static boolean muteAds() { return muteAds; }

    public static int port() { return port; }

    public static boolean toggleMuteAds() {
        muteAds = !muteAds;
        save();
        return muteAds;
    }

    public static void setPort(int p) {
        port = Math.max(1024, Math.min(65535, p));
        save();
        start();
        writeScript();
    }

    // ================= controls =================

    private static void queue(String cmd) {
        synchronized (COMMANDS) {
            if (COMMANDS.size() > 16) COMMANDS.pollFirst();
            COMMANDS.addLast(cmd);
        }
    }

    private static void control(String label, String cmd) {
        if (!connected()) {
            Actions.bar(Formatting.GRAY, "Music: no YouTube tab connected (/ds music setup)");
            return;
        }
        queue(cmd);
        Actions.bar(Formatting.GRAY, "Music: " + label);
    }

    public static void play() { control("Play", "play"); }

    public static void pause() { control("Pause", "pause"); }

    public static void togglePlay() {
        Now n = now();
        if (n != null && n.playing()) pause(); else play();
    }

    public static void next() { control("Next", "next"); }

    public static void previous() { control("Previous", "prev"); }

    public static void volume(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        control("Volume " + v + "%", "volume:" + v);
    }

    /** Manually mute or unmute the tab (separate from the automatic ad muting). */
    public static void toggleTabMute() { control("Mute / unmute tab", "mute"); }

    // ================= status + setup output =================

    public static void showStatus() {
        Now n = now();
        if (n == null) {
            Actions.chat(Formatting.GRAY, "[Music] " + statusText());
            return;
        }
        String state = n.ad() ? "AD (muted)" : (n.playing() ? "playing" : "paused");
        Actions.chat(Formatting.GREEN, "[Music] " + n.title() + (n.artist().isEmpty() ? "" : " - " + n.artist())
                + " [" + state + "] on " + n.source() + ". Mute ads: " + (muteAds ? "on" : "off"));
    }

    public static void setup() {
        writeScript();
        Path p = scriptPath();
        Actions.chat(Formatting.GOLD, "[Music] YouTube setup (works with a free account, no login):");
        Actions.chat(Formatting.WHITE, "1. Install the Tampermonkey (or Violentmonkey) browser extension.");
        Actions.chat(Formatting.WHITE, "2. Open this file, copy everything in it, and create a new userscript with it:");
        Actions.chat(Formatting.AQUA, p.toAbsolutePath().toString());
        Actions.chat(Formatting.WHITE, "3. Reload your YouTube / YouTube Music tab and play something. The panel fills in within a second.");
        Actions.chat(Formatting.GRAY, "The file contains your private token: don't share it. If you change /ds music port, run setup again.");
    }

    private static void writeScript() {
        String js = SCRIPT.replace("%PORT%", Integer.toString(port)).replace("%TOKEN%", token);
        Thread t = new Thread(() -> {
            try {
                Files.createDirectories(scriptPath().getParent());
                Files.writeString(scriptPath(), js, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("Could not write the userscript", e);
            }
        }, "donutstreamer-music-script");
        t.setDaemon(true);
        t.start();
    }

    // ================= tiny HTTP server (loopback only) =================

    private static void acceptLoop(ServerSocket s) {
        while (running && !s.isClosed()) {
            try {
                Socket c = s.accept();
                c.setSoTimeout(3000);
                Thread t = new Thread(() -> handle(c), "donutstreamer-music-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running && !s.isClosed()) LOG.debug("music accept", e);
            }
        }
    }

    private static void handle(Socket c) {
        try (c; InputStream in = c.getInputStream(); OutputStream out = c.getOutputStream()) {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int b, matched = 0;
            while (head.size() < 8192 && (b = in.read()) >= 0) {   // read headers until \r\n\r\n
                head.write(b);
                if (b == '\r' && (matched == 0 || matched == 2)) matched++;
                else if (b == '\n' && (matched == 1 || matched == 3)) matched++;
                else matched = (b == '\r') ? 1 : 0;
                if (matched == 4) break;
            }
            String header = head.toString(StandardCharsets.ISO_8859_1);
            String[] lines = header.split("\r\n");
            if (lines.length == 0) return;
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0];
            String path = req[1];
            int len = 0;
            String reqToken = "";
            for (String l : lines) {
                String low = l.toLowerCase(Locale.ROOT);
                if (low.startsWith("content-length:")) {
                    try {
                        len = Integer.parseInt(l.substring(15).trim());
                    } catch (NumberFormatException ignored) {
                        len = 0;
                    }
                } else if (low.startsWith("x-ds-token:")) {
                    reqToken = l.substring(11).trim();
                }
            }
            if (len > MAX_BODY) { reply(out, 413, "{}"); return; }
            byte[] body = in.readNBytes(len);
            if (!"/music/state".equals(path) || !"POST".equals(method) || !constantEq(reqToken, token)) {
                reply(out, 403, "{}");
                return;
            }
            reply(out, 200, onState(new String(body, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            LOG.debug("music request", e);
        }
    }

    private static boolean constantEq(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static void reply(OutputStream out, int code, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** Parses one report from the userscript and answers with settings plus any waiting commands. */
    private static String onState(String json) {
        JsonObject resp = new JsonObject();
        resp.addProperty("muteAds", muteAds);
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String tab = str(o, "tab", "");
            boolean playing = bool(o, "playing");
            // The tab that is playing owns the controls; a paused tab hands over to one that starts playing.
            long age = System.currentTimeMillis() - lastReportMs;
            if (activeTab.isEmpty() || tab.equals(activeTab) || age > STALE_MS || (playing && !activePlaying)) activeTab = tab;
            if (tab.equals(activeTab)) {
                activePlaying = playing;
                String title = clip(str(o, "title", ""), 120);
                if (title.isEmpty()) title = "YouTube";
                now = new Now(str(o, "id", title), title, clip(str(o, "artist", ""), 80),
                        Math.max(0, (long) (num(o, "duration") * 1000)), Math.max(0, (long) (num(o, "position") * 1000)),
                        playing, bool(o, "ad"), bool(o, "muted"),
                        (int) Math.round(num(o, "volume") * 100), clip(str(o, "source", "YouTube"), 24), System.nanoTime());
                lastReportMs = System.currentTimeMillis();
                everConnected = true;
                JsonArray cmds = new JsonArray();
                synchronized (COMMANDS) {
                    while (!COMMANDS.isEmpty()) cmds.add(COMMANDS.pollFirst());
                }
                resp.add("cmds", cmds);
            }
        } catch (Exception e) {
            LOG.debug("bad music report", e);
        }
        return GSON.toJson(resp);
    }

    private static String str(JsonObject o, String k, String d) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : d;
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static double num(JsonObject o, String k) {
        JsonElement e = o.get(k);
        try {
            double v = e != null && e.isJsonPrimitive() ? e.getAsDouble() : 0;
            return Double.isNaN(v) || Double.isInfinite(v) ? 0 : v;
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String clip(String s, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char ch = s.charAt(i);
            if (ch < 32 || ch == 127 || ch == '\u00a7') continue;
            sb.append(ch);
        }
        return sb.toString().trim();
    }

    // ================= the userscript (placeholders filled in per user) =================

    private static final String SCRIPT = """
// ==UserScript==
// @name         Donut Streamer - YouTube music bridge
// @namespace    donutstreamer
// @version      1.0
// @description  Shows what YouTube / YouTube Music is playing in the Donut Streamer HUD, adds mod controls, and mutes the tab while an ad plays.
// @match        https://www.youtube.com/*
// @match        https://music.youtube.com/*
// @match        https://m.youtube.com/*
// @grant        GM_xmlhttpRequest
// @connect      127.0.0.1
// @run-at       document-idle
// ==/UserScript==
(function () {
  'use strict';
  var PORT = %PORT%, TOKEN = '%TOKEN%';
  var TAB = Math.random().toString(36).slice(2);
  var muteAds = true, inAd = false, wasMutedBeforeAd = false, lastTrack = { id: '', title: '', artist: '' };
  var isMusic = location.hostname === 'music.youtube.com';

  function video() { return document.querySelector('video.html5-main-video') || document.querySelector('video'); }
  function player() { return document.getElementById('movie_player'); }

  function adShowing() {
    var p = player();
    if (p && p.classList.contains('ad-showing')) return true;
    return !!document.querySelector('.ytp-ad-player-overlay, .ytp-ad-player-overlay-layout, .ytp-ad-text');
  }

  function videoId() {
    try { var u = new URL(location.href); return u.searchParams.get('v') || (u.pathname.split('/').pop() || ''); } catch (e) { return ''; }
  }

  function track() {
    var title = '', artist = '';
    var ms = navigator.mediaSession && navigator.mediaSession.metadata;
    if (ms) { title = ms.title || ''; artist = ms.artist || ''; }
    if (!title) {
      var h = document.querySelector('ytd-watch-metadata h1, h1.ytd-watch-metadata, .title.ytmusic-player-bar');
      title = h ? h.textContent.trim() : document.title.replace(/ - YouTube( Music)?$/, '');
    }
    if (!artist) {
      var c = document.querySelector('ytd-watch-metadata #owner #channel-name a, .byline.ytmusic-player-bar');
      artist = c ? c.textContent.trim().split('\\u2022')[0].trim() : '';
    }
    return { id: videoId() || title, title: title, artist: artist };
  }

  function applyAdMute(v) {
    var ad = adShowing();
    if (ad && muteAds) {
      if (!inAd) { wasMutedBeforeAd = v.muted; inAd = true; }
      v.muted = true;
    } else if (inAd) {
      inAd = false;
      if (!wasMutedBeforeAd) v.muted = false;
    }
    return ad;
  }

  function click(sel) { var e = document.querySelector(sel); if (e) { e.click(); return true; } return false; }

  function run(cmd, v) {
    if (cmd === 'play') v.play();
    else if (cmd === 'pause') v.pause();
    else if (cmd === 'next') { click('.ytp-next-button') || click('ytmusic-player-bar .next-button') || click('.next-button'); }
    else if (cmd === 'prev') { if (!(click('ytmusic-player-bar .previous-button') || click('.ytp-prev-button'))) v.currentTime = 0; }
    else if (cmd === 'mute') v.muted = !v.muted;
    else if (cmd.indexOf('volume:') === 0) { var n = parseInt(cmd.slice(7), 10); if (!isNaN(n)) { v.volume = Math.max(0, Math.min(1, n / 100)); if (n > 0 && !inAd) v.muted = false; } }
  }

  function tick() {
    var v = video();
    if (!v) return;
    var ad = applyAdMute(v);
    if (!ad) lastTrack = track();
    var body = JSON.stringify({
      tab: TAB, source: isMusic ? 'YouTube Music' : 'YouTube',
      id: lastTrack.id, title: lastTrack.title, artist: lastTrack.artist,
      duration: ad ? 0 : (isFinite(v.duration) ? v.duration : 0), position: ad ? 0 : v.currentTime,
      playing: !v.paused && !v.ended, ad: ad, muted: v.muted, volume: v.volume
    });
    GM_xmlhttpRequest({
      method: 'POST', url: 'http://127.0.0.1:' + PORT + '/music/state', data: body, timeout: 2000,
      headers: { 'Content-Type': 'application/json', 'X-DS-Token': TOKEN },
      onload: function (r) {
        try {
          var j = JSON.parse(r.responseText);
          if (typeof j.muteAds === 'boolean') muteAds = j.muteAds;
          (j.cmds || []).forEach(function (c) { run(c, v); });
        } catch (e) {}
      },
      onerror: function () {}, ontimeout: function () {}
    });
  }
  setInterval(tick, 500);
})();
""";
}
