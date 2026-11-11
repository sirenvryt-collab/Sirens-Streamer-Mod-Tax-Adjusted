package com.donutstreamer;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * All session data + settings. Everything comes from payment messages in chat.
 * Stats are always recomputed from the history list, so undo and setting changes are exact.
 *
 * IN   = "name paid you $X"  (money received)  -> counts as a win
 * OUT  = "you paid name $X"  (money sent)      -> counts as a loss
 * SELL = "$X" from selling items (tracked separately, optionally included in profit)
 * BUY  = "You bought N item for $X" -> subtracts from live profit, but is not a win/loss/gamble
 *
 * Performance notes: nothing in here touches the disk on the game thread. Saves are debounced and
 * written on a background thread, CSV rows are queued, and everything the HUD needs every frame
 * (chart series, per-player table, top payer) is cached until the history changes.
 */
public final class Session {
    private static final Logger LOG = LoggerFactory.getLogger("donutstreamer");
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson COMPACT = new Gson();
    /** Everything except the (potentially large) history list; the history is turned into JSON on the writer thread. */
    private static final Gson NO_HISTORY = new GsonBuilder()
            .setExclusionStrategies(new ExclusionStrategy() {
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    return f.getDeclaringClass() == Session.class && "history".equals(f.getName());
                }

                @Override
                public boolean shouldSkipClass(Class<?> c) {
                    return false;
                }
            })
            .create();

    private static final long SAVE_INTERVAL_MS = 5_000L;
    private static final String CSV_HEADER = "time,type,player,amount,delta,net_after\n";

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "donutstreamer-io");
        t.setDaemon(true);
        return t;
    });

    public static final String IN = "IN";
    public static final String OUT = "OUT";
    public static final String SELL = "SELL";
    public static final String BUY = "BUY";     // "You bought 3 item for $X": subtracts from live profit, not a gamble

    public static final class Entry {
        public String kind = IN;
        public long amount;
        public String who = "";
        public long time;

        public Entry() {}

        public Entry(String kind, String who, long amount) {
            this.kind = kind;
            this.who = who == null ? "" : who;
            this.amount = amount;
            this.time = System.currentTimeMillis();
        }
    }

    public static final class WidgetCfg {
        public boolean on;
        public int x;
        public int y;
        public float scale = 1.0f;     // this element's own size, multiplied with the global HUD scale
        public int bgAlpha = 100;      // card background opacity in percent (0 = see-through)
        public int accent = 0;         // 0 = automatic colour, otherwise 0xRRGGBB override for the edge and bars

        public WidgetCfg() {}

        public WidgetCfg(boolean on, int x, int y) {
            this.on = on;
            this.x = x;
            this.y = y;
        }
    }

    public static final class PlayerStat {
        public String name;
        public long in;
        public long out;
        public int count;

        public long net() { return in - out; }
    }

    // ---- persisted ----
    public long lossLimit = 0;         // 0 = off
    public long goal = 0;              // 0 = off (take-profit alert)
    public int tiltAlert = 0;          // 0 = off (alert after N payments sent in a row)
    public double rakebackPct = 0;     // 0 = off
    public long startBalance = -1;     // -1 = not set
    public long elapsedMs = 0;
    public List<Entry> history = new ArrayList<>();

    public boolean hudVisible = true;
    public boolean sound = false;
    public boolean showNames = true;
    public boolean streamMode = false;       // master switch for the stream-safety options below
    public boolean streamHideCoords = true;  // F3: hide your position (and the block you look at) while stream mode is on
    public boolean streamHideChatCoords = false;   // blank out "x, y, z" style coordinates in incoming chat while stream mode is on
    public boolean streamHideNames = true;   // hide player names on the HUD while stream mode is on
    public boolean selfNametag = true;       // show your own nametag in third person (F5), like others see it
    public boolean selfNametagRank = true;   // also show the rank from your tab-list name on that nametag
    public boolean dailyReset = true;        // start a fresh session automatically at local midnight
    public String sessionDay = "";           // local date (yyyy-MM-dd) the current session belongs to
    public boolean chatPreviews = true;      // chat screen: draw the auction / music panels as previews when they have nothing to show
    public boolean auctionStreamDelay = false;   // add 12 seconds to every auction so a delayed stream can still bid
    public boolean watermark = true;         // small blue "Sirens Streamer Mod" text, bottom left
    public boolean percentMode = false;      // show net as % of starting balance
    public boolean includeSells = false;     // count sell income in net profit
    public boolean sellsFromActionBar = false;
    public boolean doubleTag = true;         // tag incoming payments that doubled the previous one
    public boolean trackAuctionPayments = true;   // payments that count as auction bids also go in the tracker
    public boolean auctionAnyServer = false;      // allow auction tools outside DonutSMP (testing)
    public float scale = 1.0f;

    // ---- stream chat panel (see /ds settings > Chat) ----
    public int chatWidth = 230;            // panel width in GUI pixels
    public int chatHeight = 100;           // panel height; older messages scroll off the top
    public int chatMaxLines = 0;           // lines per message, 0 = show every message in full
    public int chatFadeSec = 90;           // older lines dim after this many seconds, 0 = never dim
    public int chatLineGap = 1;            // extra pixels between lines
    public int chatTextColor = 0;          // index into ChatPanel.TEXT_COLORS
    public boolean chatShowHeader = true;
    public boolean chatShowMarker = true;  // coloured Twitch / YouTube square before each message
    public boolean chatViewerColors = true;
    public boolean chatShadow = true;

    // ---- music panel ----
    public boolean musicCover = true;
    public boolean musicEqualizer = true;

    public Map<String, WidgetCfg> widgets = new LinkedHashMap<>();

    // ---- derived (not saved) ----
    public transient long net, received, sent, sold, biggestIn, biggestOut, peak, low, spent;
    public transient int wins, losses, streak, bestWinStreak, worstLossStreak;

    /** Set when a new day started a fresh session; the previous summary is printed once a player is in a world. */
    public transient String pendingNotice;

    /** Bumped on every change to data or settings; the HUD uses it to know when cached text is stale. */
    public transient long version;
    private transient boolean dirty;
    private transient long lastFlush;

    private transient List<Long> cumCache;
    private transient List<Entry> resultsCache;
    private transient List<PlayerStat> playersCache;
    private transient PlayerStat topPayerCache;

    public WidgetCfg cfg(Widgets.W w) {
        WidgetCfg c = widgets.get(w.id);
        if (c == null) {
            int[] p = Widgets.defaultPos(w);
            c = new WidgetCfg(w.defOn, p[0], p[1]);
            widgets.put(w.id, c);
        }
        return c;
    }

    /** On-screen scale of one element: the global HUD scale times the element's own size. */
    public float scaleOf(WidgetCfg c) {
        return scale * c.scale;
    }

    /** Whether HUD text may show player names (stream mode can force them off). */
    public boolean namesVisible() {
        return showNames && !(streamMode && streamHideNames);
    }

    /** Profit change an entry causes (sells count only when enabled). */
    public long delta(Entry e) {
        if (IN.equals(e.kind)) return e.amount;
        if (OUT.equals(e.kind)) return -e.amount;
        if (BUY.equals(e.kind)) return -e.amount;
        return includeSells ? e.amount : 0;
    }

    public void recompute() {
        received = 0; sent = 0; sold = 0; spent = 0; biggestIn = 0; biggestOut = 0;
        wins = 0; losses = 0; streak = 0; bestWinStreak = 0; worstLossStreak = 0;
        peak = 0; low = 0;
        long run = 0;
        int ws = 0, ls = 0;
        for (Entry e : history) {
            if (IN.equals(e.kind)) {
                received += e.amount;
                wins++;
                ws++;
                ls = 0;
                streak = ws;
                bestWinStreak = Math.max(bestWinStreak, ws);
                biggestIn = Math.max(biggestIn, e.amount);
            } else if (OUT.equals(e.kind)) {
                sent += e.amount;
                losses++;
                ls++;
                ws = 0;
                streak = -ls;
                worstLossStreak = Math.max(worstLossStreak, ls);
                biggestOut = Math.max(biggestOut, e.amount);
            } else if (BUY.equals(e.kind)) {
                spent += e.amount;
            } else {
                sold += e.amount;
            }
            run += delta(e);
            peak = Math.max(peak, run);
            low = Math.min(low, run);
        }
        net = received - sent - spent + (includeSells ? sold : 0);

        cumCache = null;
        resultsCache = null;
        playersCache = null;
        topPayerCache = null;
        version++;
    }

    public Entry add(String kind, String who, long amount) {
        Entry e = new Entry(kind, who, amount);
        history.add(e);
        recompute();
        return e;
    }

    public Entry undo() {
        if (history.isEmpty()) return null;
        Entry e = history.remove(history.size() - 1);
        recompute();
        return e;
    }

    public void reset() {
        history.clear();
        elapsedMs = 0;
        recompute();
    }

    public int total() { return wins + losses; }

    private static boolean isDouble(long next, long prev) {
        return prev > 0 && Math.abs(next - 2 * prev) <= Math.max(1, Math.round(prev * 0.02));
    }

    /**
     * Call BEFORE adding the new incoming payment. If this player's previous payment to you was
     * exactly half of this one, and you have not paid them since, returns the doubling chain
     * (oldest first, ending with this payment), e.g. [10K, 20K, 40K]. Otherwise an empty list.
     */
    public List<Long> doubleSequence(String who, long amount) {
        List<Long> rev = new ArrayList<>();
        long next = amount;
        for (int i = history.size() - 1; i >= 0; i--) {
            Entry e = history.get(i);
            if (BUY.equals(e.kind)) continue;
            if (e.who.isEmpty() || !e.who.equalsIgnoreCase(who)) continue;
            if (!IN.equals(e.kind)) break;                 // you paid them in between: chain is broken
            if (!isDouble(next, e.amount)) break;
            if (rev.isEmpty()) rev.add(next);
            rev.add(e.amount);
            next = e.amount;
        }
        List<Long> seq = new ArrayList<>();
        for (int i = rev.size() - 1; i >= 0; i--) seq.add(rev.get(i));
        return seq;
    }

    // ---- cached views (rebuilt only after the history or a setting changes) ----

    /** Running profit after every payment, starting at 0. */
    public List<Long> cumulative() {
        if (cumCache == null) {
            List<Long> cum = new ArrayList<>(history.size() + 1);
            cum.add(0L);
            long run = 0;
            for (Entry e : history) {
                if (SELL.equals(e.kind) && !includeSells) continue;
                run += delta(e);
                cum.add(run);
            }
            cumCache = Collections.unmodifiableList(cum);
        }
        return cumCache;
    }

    /** Only the IN / OUT entries (the gambles), oldest first. */
    public List<Entry> results() {
        if (resultsCache == null) {
            List<Entry> res = new ArrayList<>();
            for (Entry e : history) {
                if (IN.equals(e.kind) || OUT.equals(e.kind)) res.add(e);
            }
            resultsCache = Collections.unmodifiableList(res);
        }
        return resultsCache;
    }

    /** Per-player totals, most active first. */
    public List<PlayerStat> playerStats() {
        if (playersCache == null) {
            Map<String, PlayerStat> map = new LinkedHashMap<>();
            for (Entry e : history) {
                if (SELL.equals(e.kind) || BUY.equals(e.kind) || e.who.isEmpty()) continue;
                PlayerStat p = map.computeIfAbsent(e.who.toLowerCase(Locale.ROOT), k -> {
                    PlayerStat n = new PlayerStat();
                    n.name = e.who;
                    return n;
                });
                p.count++;
                if (IN.equals(e.kind)) p.in += e.amount; else p.out += e.amount;
            }
            List<PlayerStat> list = new ArrayList<>(map.values());
            list.sort((a, b) -> Integer.compare(b.count, a.count));
            playersCache = Collections.unmodifiableList(list);
        }
        return playersCache;
    }

    /** The player who has sent you the most, or null. */
    public PlayerStat topPayer() {
        if (topPayerCache == null) {
            PlayerStat best = null;
            for (PlayerStat p : playerStats()) {
                if (p.in > 0 && (best == null || p.in > best.in)) best = p;
            }
            topPayerCache = best;
        }
        return topPayerCache;
    }

    public String winRate() {
        return total() == 0 ? "0.0" : String.format(Locale.ROOT, "%.1f", 100.0 * wins / total());
    }

    public String time() {
        long s = elapsedMs / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    public String streakText() {
        if (streak > 0) return "W" + streak;
        if (streak < 0) return "L" + (-streak);
        return "-";
    }

    public long rakebackOwed(PlayerStat p) {
        return Math.round(p.in * rakebackPct / 100.0);
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "Donut SMP session | Live %s | %dW-%dL (%s%%) | Received %s | Sent %s | Time %s",
                Money.signed(net), wins, losses, winRate(),
                Money.format(received), Money.format(sent), time());
    }

    // ---- change tracking / saving ----

    /** Marks a data or setting change: invalidates cached HUD text and schedules a (debounced) save. */
    public void save() {
        dirty = true;
        version++;
    }

    /** Called every client tick: writes the config in the background at most every few seconds. */
    public void tick() {
        if (dirty && System.currentTimeMillis() - lastFlush >= SAVE_INTERVAL_MS) flushAsync();
    }

    private void flushAsync() {
        // Game thread: only the small settings part becomes JSON, plus a cheap pointer copy of the history list
        // (entries are never changed after they are added). The big part is serialised and written on the IO thread,
        // so saving never causes a frame hitch however long the session gets.
        JsonObject root = NO_HISTORY.toJsonTree(this).getAsJsonObject();
        List<Entry> snapshot = new ArrayList<>(history);
        dirty = false;
        lastFlush = System.currentTimeMillis();
        Path p = configPath();
        submit(() -> {
            root.add("history", COMPACT.toJsonTree(snapshot));
            writeConfig(p, (snapshot.size() > 2000 ? COMPACT : PRETTY).toJson(root));
        });
    }

    /** Writes anything pending and waits (briefly) for the background writer. Call on game exit. */
    public void shutdown() {
        if (dirty) flushAsync();
        IO.shutdown();
        try {
            if (!IO.awaitTermination(5, TimeUnit.SECONDS)) LOG.warn("Timed out waiting for config write");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void submit(Runnable r) {
        try {
            IO.execute(r);
        } catch (RuntimeException e) {
            LOG.debug("Writer already stopped", e);
        }
    }

    // ---- files ----
    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("donutstreamer.json");
    }

    private static Path csvPath() {
        return FabricLoader.getInstance().getGameDir().resolve("donutstreamer").resolve("bets.csv");
    }

    private static void writeConfig(Path p, String json) {
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("Could not save config", e);
        }
    }

    public static Session load() {
        Path p = configPath();
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                Session s = PRETTY.fromJson(r, Session.class);
                if (s != null) {
                    s.sanitize();
                    s.recompute();
                    return s;
                }
            } catch (Exception e) {
                LOG.warn("Could not read config, starting fresh (old file kept as donutstreamer.json.corrupt)", e);
                try {
                    Files.move(p, p.resolveSibling("donutstreamer.json.corrupt"), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // nothing more we can do
                }
            }
        }
        Session s = new Session();
        s.recompute();
        return s;
    }

    /** Repairs hand-edited or older config files so the HUD never sees impossible values. */
    private void sanitize() {
        if (history == null) history = new ArrayList<>();
        if (widgets == null) widgets = new LinkedHashMap<>();
        history.removeIf(e -> e == null || e.kind == null || e.who == null || e.amount < 0);
        widgets.values().removeIf(c -> c == null);
        if (!(scale >= 0.5f && scale <= 3.0f)) scale = 1.0f;
        for (WidgetCfg c : widgets.values()) {
            if (!(c.scale >= 0.4f && c.scale <= 4.0f)) c.scale = 1.0f;
            if (c.bgAlpha < 0 || c.bgAlpha > 100) c.bgAlpha = 100;
            c.accent &= 0xFFFFFF;
        }
        chatWidth = Math.max(140, Math.min(520, chatWidth));
        chatHeight = Math.max(50, Math.min(400, chatHeight));
        chatMaxLines = Math.max(0, Math.min(12, chatMaxLines));
        chatFadeSec = Math.max(0, Math.min(600, chatFadeSec));
        chatLineGap = Math.max(0, Math.min(4, chatLineGap));
        if (chatTextColor < 0 || chatTextColor > 4) chatTextColor = 0;
        if (lossLimit < 0) lossLimit = 0;
        if (goal < 0) goal = 0;
        if (tiltAlert != 0 && (tiltAlert < 2 || tiltAlert > 20)) tiltAlert = 0;
        if (!(rakebackPct >= 0 && rakebackPct <= 50)) rakebackPct = 0;
    }

    /** Appends one line to <game dir>/donutstreamer/sessions.log (summary of a finished session). */
    public void logSessionEnd(String why, String summary) {
        String line = Instant.now() + " [" + why + "] " + summary + "\n";
        Path p = csvPath().resolveSibling("sessions.log");
        submit(() -> {
            try {
                Files.createDirectories(p.getParent());
                Files.writeString(p, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                LOG.warn("Could not write sessions.log", e);
            }
        });
    }

    /** Queues a row for <game dir>/donutstreamer/bets.csv (written on the background thread). */
    public void logCsv(String type, String who, long amount, long delta) {
        String line = Instant.now() + "," + csv(type) + "," + csv(who) + "," + amount + "," + delta + "," + net + "\n";
        Path p = csvPath();
        submit(() -> appendCsv(p, line));
    }

    private static void appendCsv(Path p, String line) {
        try {
            Files.createDirectories(p.getParent());
            boolean fresh = !Files.exists(p) || Files.size(p) == 0;
            try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) w.write(CSV_HEADER);
                w.write(line);
            }
        } catch (IOException e) {
            LOG.warn("Could not write bets.csv", e);
        }
    }

    /** Quotes a CSV field and defuses spreadsheet formulas (item names come from chat). */
    private static String csv(String s) {
        if (s == null || s.isEmpty()) return "";
        String v = s;
        char c = v.charAt(0);
        if (c == '=' || c == '+' || c == '-' || c == '@') v = "'" + v;
        if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
            v = "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }
}
