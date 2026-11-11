package com.donutstreamer.auction;

import com.donutstreamer.DonutStreamerClient;
import com.donutstreamer.Session;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The single running auction: item, minimum bid, countdown and top bid.
 * The timer is never extended; once started it only counts down, however many bids come in.
 */
public final class AuctionState {
    public static final AuctionState INSTANCE = new AuctionState();

    /** Length of the separate stream-delay countdown that follows the main countdown (when the option is on). */
    public static final int STREAM_DELAY_SECONDS = 12;

    public static final int BID_REJECTED = 0;   // no auction, below minimum, or not a valid amount
    public static final int BID_ACCEPTED = 1;   // valid bid, but not the highest
    public static final int BID_TOP = 2;        // new highest bid

    /** Starting bid used for items that aren't in the curated price list. */
    private static final long DEFAULT_STARTING_BID = 10_000L;

    private static final String NO_BIDDER = "Nobody yet";

    private final List<AuctionItem> curated = new ArrayList<>();

    private String currentItemName = "";
    private Item currentItemIcon = null;
    private ItemStack currentStack = null;
    private String highestBidder = NO_BIDDER;
    private long highestBid = 0;
    private long minimumBid = 0;
    private long auctionEndTimeMillis;
    private long durationMillis;
    private long delayMillis;      // 0, or STREAM_DELAY_SECONDS * 1000 when the stream-delay option was on at start
    private boolean active = false;

    /** Bumped on every visible change, so the HUD only rebuilds its text when something happened. */
    private long revision = 0;

    /** Full browsable list (curated items first, then every other vanilla item). Built once. */
    private List<AuctionItem> allItems = null;

    /** Result of an auction that just finished, waiting to be announced in chat. */
    private AuctionResult pendingResult = null;

    /** Snapshot of how an auction ended. */
    public record AuctionResult(String itemName, String winner, long winningBid) {
        public boolean hasWinner() {
            return winner != null && winningBid > 0;
        }
    }

    private AuctionState() {
        seedCuratedItems();
    }

    public synchronized void start(String itemName, long minimumBid, int durationSec) {
        start(itemName, resolveItemIcon(itemName), minimumBid, durationSec);
    }

    public synchronized void start(String itemName, Item icon, long minimumBid, int durationSec) {
        this.currentItemName = itemName;
        this.currentItemIcon = icon;
        this.currentStack = icon == null ? null : new ItemStack(icon);
        this.pendingResult = null;
        this.minimumBid = Math.max(0, minimumBid);
        Session cfg = DonutStreamerClient.session;
        this.delayMillis = (cfg != null && cfg.auctionStreamDelay) ? STREAM_DELAY_SECONDS * 1000L : 0L;
        this.durationMillis = Math.max(1, durationSec) * 1000L;   // fixed once at start; never extended afterwards
        this.active = true;
        this.auctionEndTimeMillis = System.currentTimeMillis() + durationMillis;
        this.highestBid = 0;
        this.highestBidder = NO_BIDDER;
        revision++;
    }

    /** Ends the running auction now (the result is announced on the next tick). */
    public synchronized void stop() {
        if (active) finishAuction();
    }

    public synchronized boolean isActive() {
        if (active && System.currentTimeMillis() >= auctionEndTimeMillis + delayMillis) finishAuction();
        return active;
    }

    private void finishAuction() {
        active = false;
        pendingResult = new AuctionResult(currentItemName, highestBidder, highestBid);
        revision++;
    }

    /** Returns the result of a just-finished auction exactly once, then null until the next one ends. */
    public synchronized AuctionResult takePendingResult() {
        AuctionResult result = pendingResult;
        pendingResult = null;
        return result;
    }

    /**
     * Called for every "name paid you $ amount" chat message while an auction runs.
     * A bid counts when it meets the minimum; it becomes the top bid when it beats the current one.
     */
    public synchronized int registerBid(String bidderName, long amount) {
        if (!isActive() || amount <= 0 || amount < minimumBid) return BID_REJECTED;
        if (amount > highestBid) {
            highestBid = amount;
            highestBidder = bidderName;
            revision++;
            return BID_TOP;
        }
        return BID_ACCEPTED;
    }

    public synchronized long revision() { return revision; }

    /** Time left on the MAIN countdown (0 once it has run out, including during the stream-delay countdown). */
    public synchronized long getRemainingMillis() {
        return active ? Math.max(0, auctionEndTimeMillis - System.currentTimeMillis()) : 0;
    }

    /** True while the separate stream-delay countdown is running (main countdown over, bids still count). */
    public synchronized boolean isInDelay() {
        return active && delayMillis > 0 && System.currentTimeMillis() >= auctionEndTimeMillis;
    }

    /** Time left on the stream-delay countdown (the full delay until the main countdown ends). */
    public synchronized long getDelayRemainingMillis() {
        if (!active || delayMillis <= 0) return 0;
        return Math.min(delayMillis, Math.max(0, auctionEndTimeMillis + delayMillis - System.currentTimeMillis()));
    }

    public synchronized long getDelayMillis() { return delayMillis; }

    public synchronized boolean hasBid() { return highestBid > 0; }

    public synchronized long getDurationMillis() { return durationMillis; }

    public synchronized long getMinimumBid() { return minimumBid; }

    public synchronized String getHighestBidder() { return highestBidder; }

    public synchronized long getHighestBid() { return highestBid; }

    public synchronized String getCurrentItemName() { return currentItemName; }

    public synchronized Item getCurrentItemIcon() { return currentItemIcon; }

    public synchronized ItemStack getCurrentStack() { return currentStack; }

    /** Every vanilla item, curated ones first. Built once (call early to avoid a hitch on first open). */
    public synchronized List<AuctionItem> getItems() {
        if (allItems == null) {
            List<AuctionItem> built = new ArrayList<>(curated);
            Set<Item> seen = new HashSet<>();
            for (AuctionItem item : curated) seen.add(item.item());

            List<AuctionItem> rest = new ArrayList<>();
            for (Item item : Registries.ITEM) {
                if (item == Items.AIR || seen.contains(item)) continue;
                rest.add(new AuctionItem(item.getName().getString(), item, DEFAULT_STARTING_BID));
            }
            rest.sort((a, b) -> a.displayName().compareToIgnoreCase(b.displayName()));
            built.addAll(rest);
            allItems = Collections.unmodifiableList(built);
        }
        return allItems;
    }

    private Item resolveItemIcon(String name) {
        if (name == null || name.isBlank()) return null;

        String normalized = name.trim().toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replaceAll("[^a-z0-9_]", "");

        if (!normalized.isEmpty()) {
            Identifier id = Identifier.of("minecraft", normalized);
            Item registryMatch = Registries.ITEM.get(id);
            if (registryMatch != Items.AIR) return registryMatch;
        }

        String query = name.trim().toLowerCase(Locale.ROOT);
        for (AuctionItem item : curated) {
            if (item.searchKey().equals(query)) return item.item();
        }
        for (AuctionItem item : curated) {
            if (item.searchKey().contains(query) || query.contains(item.searchKey())) return item.item();
        }
        return null;
    }

    private void seedCuratedItems() {
        curated.add(new AuctionItem("Netherite Upgrade Template", Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 250_000L));
        curated.add(new AuctionItem("Totem of Undying", Items.TOTEM_OF_UNDYING, 500_000L));
        curated.add(new AuctionItem("Enchanted Golden Apple", Items.ENCHANTED_GOLDEN_APPLE, 750_000L));
        curated.add(new AuctionItem("Nether Star", Items.NETHER_STAR, 1_200_000L));
        curated.add(new AuctionItem("Elytra", Items.ELYTRA, 3_000_000L));
        curated.add(new AuctionItem("Dragon Egg", Items.DRAGON_EGG, 15_000_000L));
        curated.add(new AuctionItem("Trident", Items.TRIDENT, 900_000L));
        curated.add(new AuctionItem("Mace", Items.MACE, 4_500_000L));
        curated.add(new AuctionItem("Diamond Block", Items.DIAMOND_BLOCK, 40_000L));
        curated.add(new AuctionItem("Netherite Block", Items.NETHERITE_BLOCK, 380_000L));
        curated.add(new AuctionItem("Beacon", Items.BEACON, 220_000L));
        curated.add(new AuctionItem("Shulker Box", Items.SHULKER_BOX, 60_000L));
        curated.add(new AuctionItem("Enchanted Book (Mending)", Items.ENCHANTED_BOOK, 180_000L));
        curated.add(new AuctionItem("Wither Skeleton Skull", Items.WITHER_SKELETON_SKULL, 95_000L));
        curated.add(new AuctionItem("Ancient Debris", Items.ANCIENT_DEBRIS, 25_000L));
    }
}
