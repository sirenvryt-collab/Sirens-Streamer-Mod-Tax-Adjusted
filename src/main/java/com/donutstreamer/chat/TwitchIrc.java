package com.donutstreamer.chat;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Read-only Twitch chat over the public IRC endpoint, signed in anonymously (justinfanNNNN), so no account or
 * token is needed and nothing can be posted. Runs on its own daemon thread and reconnects with a growing delay.
 */
final class TwitchIrc implements Runnable {
    private final String channel;
    private volatile boolean stop;
    private volatile SSLSocket socket;

    TwitchIrc(String channel) {
        this.channel = channel.toLowerCase();
    }

    void start() {
        Thread t = new Thread(this, "donutstreamer-twitch");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        stop = true;
        SSLSocket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    @Override
    public void run() {
        long backoff = 3_000;
        while (!stop) {
            try (SSLSocket s = (SSLSocket) SSLSocketFactory.getDefault().createSocket("irc.chat.twitch.tv", 6697)) {
                socket = s;
                s.setSoTimeout(7 * 60_000);   // Twitch pings about every 5 minutes; silence longer than this means a dead link
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                send(out, "CAP REQ :twitch.tv/tags");
                send(out, "PASS SCHMOOPIIE");
                send(out, "NICK justinfan" + (10000 + ThreadLocalRandom.current().nextInt(80000)));
                send(out, "JOIN #" + channel);
                StreamChat.setTwitchStatus("Connecting to #" + channel + "...");

                String line;
                while (!stop && (line = in.readLine()) != null) {
                    handle(line, out);
                    backoff = 3_000;
                }
            } catch (IOException e) {
                if (!stop) StreamChat.setTwitchStatus("Twitch disconnected, retrying...");
            } finally {
                socket = null;
            }
            if (stop) break;
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(60_000, backoff * 2);
        }
    }

    private static void send(BufferedWriter out, String line) throws IOException {
        out.write(line);
        out.write("\r\n");
        out.flush();
    }

    private void handle(String line, BufferedWriter out) throws IOException {
        if (line.startsWith("PING")) {
            send(out, "PONG :tmi.twitch.tv");
            return;
        }
        String tags = "";
        String rest = line;
        if (rest.startsWith("@")) {
            int sp = rest.indexOf(' ');
            if (sp < 0) return;
            tags = rest.substring(1, sp);
            rest = rest.substring(sp + 1);
        }
        if (rest.contains(" 366 ")) {
            StreamChat.setTwitchStatus("Connected to #" + channel);
            return;
        }
        int cmd = rest.indexOf(" PRIVMSG ");
        if (cmd < 0) return;
        int msgStart = rest.indexOf(" :", cmd);
        if (msgStart < 0) return;
        String text = rest.substring(msgStart + 2);

        String login = rest.startsWith(":") && rest.indexOf('!') > 0 ? rest.substring(1, rest.indexOf('!')) : "user";
        String display = login;
        int color = 0xFFB0B8C4;
        for (String tag : tags.split(";")) {
            int eq = tag.indexOf('=');
            if (eq < 0) continue;
            String k = tag.substring(0, eq);
            String v = tag.substring(eq + 1);
            if (k.equals("display-name") && !v.isEmpty()) display = v.replace("\\s", " ");
            else if (k.equals("color") && v.length() == 7 && v.charAt(0) == '#') {
                try { color = 0xFF000000 | Integer.parseInt(v.substring(1), 16); } catch (NumberFormatException ignored) { }
            }
        }
        if (text.startsWith("\u0001ACTION ") && text.endsWith("\u0001")) {
            text = text.substring(8, text.length() - 1);
        }
        StreamChat.add(new ChatMessage(ChatMessage.TWITCH, display, text, color));
    }
}
