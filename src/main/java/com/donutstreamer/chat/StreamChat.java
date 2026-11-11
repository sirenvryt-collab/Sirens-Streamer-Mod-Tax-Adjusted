package com.donutstreamer.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional on-screen Twitch / YouTube chat. Nothing connects until you run /ds chat twitch <channel> or
 * /ds chat youtube <video>. Messages land in a small ring buffer that the HUD panel reads; everything network
 * related runs on daemon threads and only ever reads chat (it cannot post).
 */
public final class StreamChat {
    private StreamChat() {}

    private static final Logger LOG = LoggerFactory.getLogger("donutstreamer");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern CHANNEL = Pattern.compile("[A-Za-z0-9_]{3,25}");
    private static final Pattern VIDEO_ID = Pattern.compile("(?:v=|youtu\\.be/|/live/|/shorts/)([A-Za-z0-9_-]{11})");
    private static final Pattern BARE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private static final int CAPACITY = 60;
    private static final ChatMessage[] RING = new ChatMessage[CAPACITY];
    private static int head = 0;      // next write slot
    private static int count = 0;
    private static long revision = 0;

    private static String twitchChannel = "";
    private static String youtubeKey = "";
    private static String youtubeVideo = "";
    private static boolean hideCommands = true;

    private static TwitchIrc twitch;
    private static YouTubeChat youtube;
    private static volatile String twitchStatus = "";
    private static volatile String youtubeStatus = "";

    // ---------------- lifecycle ----------------

    /** Loads the saved settings and reconnects whatever you had set up. Does nothing if you never configured chat. */
    public static void init() {
        load();
        if (!twitchChannel.isEmpty()) startTwitch();
        if (!youtubeKey.isEmpty() && !youtubeVideo.isEmpty()) startYouTube();
    }

    public static void shutdown() {
        stopTwitch();
        stopYouTube();
    }

    // ---------------- queries (HUD + commands) ----------------

    /** True when at least one chat source is set up (so the HUD panel has something to say). */
    public static boolean configured() {
        return !twitchChannel.isEmpty() || (!youtubeVideo.isEmpty() && !youtubeKey.isEmpty());
    }

    public static boolean twitchOn() { return twitch != null; }

    public static boolean youtubeOn() { return youtube != null; }

    public static String twitchChannel() { return twitchChannel; }

    public static String youtubeVideo() { return youtubeVideo; }

    public static boolean hasYouTubeKey() { return !youtubeKey.isEmpty(); }

    public static boolean hideCommands() { return hideCommands; }

    public static String statusLine() {
        String t = twitch != null ? twitchStatus : "";
        String y = youtube != null ? youtubeStatus : "";
        if (!t.isEmpty() && !y.isEmpty()) return t + "  |  " + y;
        if (!t.isEmpty()) return t;
        if (!y.isEmpty()) return y;
        return "Not connected";
    }

    static void setTwitchStatus(String s) { twitchStatus = s; }

    static void setYouTubeStatus(String s) { youtubeStatus = s; }

    public static synchronized long revision() { return revision; }

    /** Copies up to out.length of the newest messages into out (oldest first). Returns how many. */
    static synchronized int newest(ChatMessage[] out) {
        int n = Math.min(count, out.length);
        for (int i = 0; i < n; i++) {
            int idx = (head - n + i + CAPACITY * 2) % CAPACITY;
            out[i] = RING[idx];
        }
        return n;
    }

    // ---------------- commands ----------------

    /** @return an error message, or null when started. */
    public static String setTwitch(String channel) {
        String c = channel == null ? "" : channel.trim().replace("#", "");
        int slash = c.lastIndexOf('/');
        if (slash >= 0) c = c.substring(slash + 1);   // accepts https://twitch.tv/name
        if (!CHANNEL.matcher(c).matches()) return "That doesn't look like a Twitch channel name.";
        stopTwitch();
        twitchChannel = c.toLowerCase();
        startTwitch();
        saveAsync();
        return null;
    }

    public static void clearTwitch() {
        stopTwitch();
        twitchChannel = "";
        twitchStatus = "";
        saveAsync();
    }

    /** @return an error message, or null when started (or saved while waiting for a key). */
    public static String setYouTube(String input) {
        String v = parseVideoId(input);
        if (v == null) return "Give me the live stream's link or its 11-character video id.";
        stopYouTube();
        youtubeVideo = v;
        saveAsync();
        if (!youtubeKey.isEmpty()) startYouTube();
        return null;
    }

    public static void clearYouTube() {
        stopYouTube();
        youtubeVideo = "";
        youtubeStatus = "";
        saveAsync();
    }

    public static String setYouTubeKey(String key) {
        String k = key == null ? "" : key.trim();
        if (k.length() < 20 || k.length() > 80 || !k.matches("[A-Za-z0-9_-]+")) {
            return "That doesn't look like a YouTube API key (letters, digits, - and _ only).";
        }
        youtubeKey = k;
        saveAsync();
        if (!youtubeVideo.isEmpty()) {
            stopYouTube();
            startYouTube();
        }
        return null;
    }

    public static void clearYouTubeKey() {
        stopYouTube();
        youtubeKey = "";
        youtubeStatus = "";
        saveAsync();
    }

    public static boolean toggleHideCommands() {
        hideCommands = !hideCommands;
        saveAsync();
        return hideCommands;
    }

    public static void disconnectAll() {
        stopTwitch();
        stopYouTube();
        twitchStatus = "";
        youtubeStatus = "";
    }

    public static synchronized void clearMessages() {
        for (int i = 0; i < CAPACITY; i++) RING[i] = null;
        head = 0;
        count = 0;
        revision++;
    }

    static String parseVideoId(String input) {
        if (input == null) return null;
        String s = input.trim();
        Matcher m = VIDEO_ID.matcher(s);
        if (m.find()) return m.group(1);
        return BARE_ID.matcher(s).matches() ? s : null;
    }

    // ---------------- connections ----------------

    private static void startTwitch() {
        stopTwitch();
        twitchStatus = "Connecting to #" + twitchChannel + "...";
        twitch = new TwitchIrc(twitchChannel);
        twitch.start();
    }

    private static void stopTwitch() {
        if (twitch != null) twitch.stop();
        twitch = null;
    }

    private static void startYouTube() {
        stopYouTube();
        youtubeStatus = "Connecting to YouTube...";
        youtube = new YouTubeChat(youtubeKey, youtubeVideo);
        youtube.start();
    }

    private static void stopYouTube() {
        if (youtube != null) youtube.stop();
        youtube = null;
    }

    // ---------------- messages ----------------

    static void add(ChatMessage m0) {
        String text = clean(m0.text, 500);
        String name = clean(m0.name, 25);
        if (text.isEmpty()) return;
        if (hideCommands && text.startsWith("!")) return;
        ChatMessage m = new ChatMessage(m0.platform, name.isEmpty() ? "user" : name, text, m0.color);
        synchronized (StreamChat.class) {
            RING[head] = m;
            head = (head + 1) % CAPACITY;
            if (count < CAPACITY) count++;
            revision++;
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127 || c == '\u00a7') continue;   // control characters and Minecraft colour codes
            sb.append(c);
        }
        return sb.toString().trim();
    }

    // ---------------- settings file ----------------

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutstreamer-chat.json");
    }

    private static void load() {
        Path p = file();
        if (!Files.exists(p)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("twitch")) twitchChannel = o.get("twitch").getAsString();
            if (o.has("youtubeKey")) youtubeKey = o.get("youtubeKey").getAsString();
            if (o.has("youtubeVideo")) youtubeVideo = o.get("youtubeVideo").getAsString();
            if (o.has("hideCommands")) hideCommands = o.get("hideCommands").getAsBoolean();
            if (!twitchChannel.isEmpty() && !CHANNEL.matcher(twitchChannel).matches()) twitchChannel = "";
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not read donutstreamer-chat.json", e);
        }
    }

    private static void saveAsync() {
        JsonObject o = new JsonObject();
        o.addProperty("twitch", twitchChannel);
        o.addProperty("youtubeKey", youtubeKey);
        o.addProperty("youtubeVideo", youtubeVideo);
        o.addProperty("hideCommands", hideCommands);
        String json = GSON.toJson(o);
        Path p = file();
        Thread t = new Thread(() -> {
            try {
                Files.writeString(p, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.warn("Could not write donutstreamer-chat.json", e);
            }
        }, "donutstreamer-chat-io");
        t.setDaemon(true);
        t.start();
    }
}
