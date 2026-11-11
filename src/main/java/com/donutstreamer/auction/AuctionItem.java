package com.donutstreamer.auction;

import com.donutstreamer.Money;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.Locale;

/**
 * A single browsable entry in the auction item list. Search key, price text and the icon stack are
 * prepared once so the browser never allocates or lower-cases anything per frame.
 */
public final class AuctionItem {
    private final String displayName;
    private final Item item;
    private final long startingBid;
    private final String searchKey;
    private final String priceText;
    private ItemStack stack;

    public AuctionItem(String displayName, Item item, long startingBid) {
        this.displayName = displayName;
        this.item = item;
        this.startingBid = startingBid;
        this.searchKey = displayName.toLowerCase(Locale.ROOT);
        this.priceText = "$" + Money.format(startingBid);
    }

    public String displayName() { return displayName; }

    public Item item() { return item; }

    public long startingBid() { return startingBid; }

    public String searchKey() { return searchKey; }

    public String priceText() { return priceText; }

    public ItemStack stack() {
        if (stack == null) stack = new ItemStack(item);
        return stack;
    }
}
