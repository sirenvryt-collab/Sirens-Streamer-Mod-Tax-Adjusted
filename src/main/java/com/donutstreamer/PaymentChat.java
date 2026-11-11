package com.donutstreamer;

import com.donutstreamer.auction.AuctionState;
import com.donutstreamer.auction.ServerDetector;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads DonutSMP payment messages from game (system) chat, records them, feeds auction bids to the
 * auction, and adds a [DOUBLED ...] tag to an incoming payment when that player doubled their
 * previous payment without you paying them in between. The tag is display-only; nothing is sent anywhere.
 *
 *   "name paid you $ amount"  -> money received (and an auction bid while an auction runs)
 *   "You paid name $ amount"  -> money sent
 *   "$ amount" alone          -> money from selling
 *   "You bought N item for $ amount" -> subtracts from live profit (not a win/loss/gamble)
 *
 * Tracker payments under 10M are ignored completely (not a win, loss or gamble). Auction bids are
 * not subject to that minimum: they only need to meet the auction's own minimum bid.
 *
 * Payment lines must START with the pattern, so a player typing a fake "x paid you $ 1m"
 * inside a chat line (which starts with their name or rank) is neither counted nor treated as a bid.
 */
public final class PaymentChat {
    private PaymentChat() {}

    // "$ 1,500", "$1,500", "$ 2.5m" are all accepted
    private static final String AMT = "\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?[kKmMbBtT]?)";
    private static final Pattern RECEIVED = Pattern.compile("^\\s*([A-Za-z0-9_.]{1,16}) paid you " + AMT);
    private static final Pattern SENT = Pattern.compile("^\\s*You paid ([A-Za-z0-9_.]{1,16}) " + AMT
            + "(?:\\s*\\(\\s*\\+?\\s*" + AMT + "\\s*tax\\s*\\))?");
    private static final Pattern BOUGHT = Pattern.compile("(?i)^\\s*You bought\\s+([0-9][0-9,]*)\\s+(.+?)\\s+for\\s+" + AMT);
    private static final Pattern SELL_ONLY = Pattern.compile("^\\s*\\+?\\s*" + AMT + "\\s*$");
    private static final Pattern SELL_TEXT = Pattern.compile("(?i)^\\s*(?:you\\s+)?sold\\b.*?" + AMT);

    /** Tracker payments below this are ignored: not a win/loss, not a gamble, not in the history. */
    private static final long MIN_PAYMENT = 10_000_000L;

    private static String lastOverlay = "";
    private static long lastOverlayAt = 0;

    /** Registered as ClientReceiveMessageEvents.MODIFY_GAME. Returns the (possibly tagged) message. */
    public static Text modify(Text message, boolean overlay) {
        Session s = DonutStreamerClient.session;
        if (s == null) return message;
        String plain = message.getString();

        if (overlay) {
            // Action bar: only used for sells, and only if you picked that source.
            if (!s.sellsFromActionBar || plain.indexOf('$') < 0) return message;
            long now = System.currentTimeMillis();
            if (plain.equals(lastOverlay) && now - lastOverlayAt < 3000) return message;   // servers often resend
            lastOverlay = plain;
            lastOverlayAt = now;
            trySell(plain);
            return message;
        }

        // Every pattern below needs a '$'. Most chat lines have none, so skip them without running a regex.
        if (plain.indexOf('$') < 0) return message;

        Matcher m = RECEIVED.matcher(plain);
        if (m.find()) {
            String who = m.group(1);
            long amount = parse(m.group(2));
            if (amount <= 0) return message;

            boolean wasBid = handleBid(who, amount);
            if (wasBid && !s.trackAuctionPayments) return message;
            if (amount < MIN_PAYMENT) return message;

            List<Long> seq = s.doubleSequence(who, amount);   // must be computed before recording
            Actions.record(Session.IN, who, amount);
            if (s.doubleTag && !seq.isEmpty()) return message.copy().append(doubleTag(seq));
            return message;
        }

        m = SENT.matcher(plain);
        if (m.find()) {
            long amount = parse(m.group(2));
            // "You paid name $ amount (+$ taxamount tax)": the tax is money you lost too
            if (m.group(3) != null) amount += parse(m.group(3));
            if (amount >= MIN_PAYMENT) Actions.record(Session.OUT, m.group(1), amount);
            return message;
        }

        m = BOUGHT.matcher(plain);
        if (m.find()) {
            long amount = parse(m.group(3));
            if (amount > 0) {
                String item = m.group(1).replace(",", "") + "x " + m.group(2).trim().replace('_', ' ');
                Actions.record(Session.BUY, item, amount);
            }
            return message;
        }

        if (!s.sellsFromActionBar) trySell(plain);
        return message;
    }

    /** Offers a payment to the running auction. @return true when it counted as a bid. */
    private static boolean handleBid(String who, long amount) {
        if (!ServerDetector.isDonutSmp()) return false;
        int result = AuctionState.INSTANCE.registerBid(who, amount);
        if (result == AuctionState.BID_REJECTED) return false;
        if (result == AuctionState.BID_TOP) Actions.auctionNewTopBid(who, amount);
        return true;
    }

    private static Text doubleTag(List<Long> seq) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < seq.size(); i++) {
            if (i > 0) sb.append(" -> ");
            sb.append(Money.format(seq.get(i)));
        }
        int doublings = seq.size() - 1;
        String text = doublings == 1
                ? " [DOUBLED: " + sb + "]"
                : " [DOUBLED x" + doublings + " in a row: " + sb + "]";
        MutableText tag = Text.literal(text);
        return tag.formatted(Formatting.GOLD, Formatting.BOLD);
    }

    private static void trySell(String plain) {
        Matcher m = SELL_ONLY.matcher(plain);
        if (!m.find()) {
            m = SELL_TEXT.matcher(plain);
            if (!m.find()) return;
        }
        long amount = parse(m.group(1));
        if (amount > 0) Actions.record(Session.SELL, "", amount);
    }

    private static long parse(String amountText) {
        try {
            return Money.parse(amountText);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
