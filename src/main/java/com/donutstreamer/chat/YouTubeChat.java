package com.donutstreamer.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Read-only YouTube live chat through the YouTube Data API v3 (needs your own API key and the live video).
 * Polls at the interval YouTube asks for (never faster than 6s) on a daemon thread. Each poll costs a few quota
 * units of the 10,000 free per day, which is several hours of chat.
 */
final class YouTubeChat implements Runnable {
    private static final String API = "https://www.googleapis.com/youtube/v3/";

    private final String apiKey;
    private final String videoId;
    private volatile boolean stop;
    private Thread thread;

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    YouTubeChat(String apiKey, String videoId) {
        this.apiKey = apiKey;
        this.videoId = videoId;
    }

    void start() {
        thread = new Thread(this, "donutstreamer-youtube");
        thread.setDaemon(true);
        thread.start();
    }

    void stop() {
        stop = true;
        if (thread != null) thread.interrupt();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonObject get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonObject body;
        try {
            body = JsonParser.parseString(res.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Unreadable reply from YouTube (HTTP " + res.statusCode() + ")");
        }
        if (res.statusCode() >= 400) {
            String reason = "HTTP " + res.statusCode();
            try {
                JsonObject err = body.getAsJsonObject("error");
                reason = err.get("message").getAsString();
                JsonArray errs = err.getAsJsonArray("errors");
                if (errs != null && errs.size() > 0 && errs.get(0).getAsJsonObject().has("reason")) {
                    reason = errs.get(0).getAsJsonObject().get("reason").getAsString() + ": " + reason;
                }
            } catch (RuntimeException ignored) { }
            throw new IOException(reason);
        }
        return body;
    }

    @Override
    public void run() {
        try {
            StreamChat.setYouTubeStatus("Finding the live chat...");
            JsonObject v = get(API + "videos?part=liveStreamingDetails&id=" + enc(videoId) + "&key=" + enc(apiKey));
            JsonArray items = v.getAsJsonArray("items");
            String chatId = null;
            if (items != null && items.size() > 0) {
                JsonObject details = items.get(0).getAsJsonObject().getAsJsonObject("liveStreamingDetails");
                if (details != null && details.has("activeLiveChatId")) chatId = details.get("activeLiveChatId").getAsString();
            }
            if (chatId == null) {
                StreamChat.setYouTubeStatus("No active live chat on that video (is it live yet?)");
                return;
            }
            StreamChat.setYouTubeStatus("Connected to YouTube chat");

            String pageToken = null;
            boolean first = true;
            long failures = 0;
            while (!stop) {
                long wait = 6_000;
                try {
                    String url = API + "liveChat/messages?liveChatId=" + enc(chatId)
                            + "&part=snippet,authorDetails&maxResults=200&key=" + enc(apiKey)
                            + (pageToken != null ? "&pageToken=" + enc(pageToken) : "");
                    JsonObject res = get(url);
                    failures = 0;
                    JsonArray msgs = res.getAsJsonArray("items");
                    if (msgs != null) {
                        int from = first ? Math.max(0, msgs.size() - 5) : 0;   // don't replay the whole backlog on connect
                        for (int i = from; i < msgs.size(); i++) handle(msgs.get(i));
                    }
                    first = false;
                    if (res.has("nextPageToken")) pageToken = res.get("nextPageToken").getAsString();
                    if (res.has("pollingIntervalMillis")) wait = Math.max(wait, res.get("pollingIntervalMillis").getAsLong());
                } catch (IOException e) {
                    String m = String.valueOf(e.getMessage());
                    if (m.contains("quotaExceeded") || m.contains("liveChatEnded") || m.contains("liveChatNotFound")
                            || m.contains("forbidden") || m.contains("keyInvalid")) {
                        StreamChat.setYouTubeStatus("YouTube chat stopped: " + m);
                        return;
                    }
                    failures++;
                    StreamChat.setYouTubeStatus("YouTube error, retrying: " + m);
                    wait = Math.min(60_000, 6_000 * (failures + 1));
                }
                Thread.sleep(wait);
            }
        } catch (InterruptedException e) {
            // stopped
        } catch (IOException e) {
            StreamChat.setYouTubeStatus("YouTube chat failed: " + e.getMessage());
        }
    }

    private void handle(JsonElement el) {
        try {
            JsonObject o = el.getAsJsonObject();
            JsonObject snip = o.getAsJsonObject("snippet");
            if (!"textMessageEvent".equals(snip.get("type").getAsString())) return;
            String text = snip.get("displayMessage").getAsString();
            JsonObject a = o.getAsJsonObject("authorDetails");
            String name = a.get("displayName").getAsString();
            boolean isOwner = a.has("isChatOwner") && a.get("isChatOwner").getAsBoolean();
            boolean mod = a.has("isChatModerator") && a.get("isChatModerator").getAsBoolean();
            int color = isOwner ? 0xFFFFD700 : (mod ? 0xFF5E84F1 : 0xFFB0B8C4);
            StreamChat.add(new ChatMessage(ChatMessage.YOUTUBE, name, text, color));
        } catch (RuntimeException ignored) {
            // malformed item: skip it
        }
    }
}
