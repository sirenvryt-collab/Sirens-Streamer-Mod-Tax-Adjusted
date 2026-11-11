package com.donutstreamer.auction;

import com.donutstreamer.Actions;
import com.donutstreamer.Money;
import com.donutstreamer.Ui;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen auction item browser: a search/filter bar at the top and a
 * scrollable, hoverable list of items below it.
 * <p>
 * Clicking a row opens a setup panel where you choose the minimum bid and the
 * auction time, then press "Start Auction".
 */
public class AuctionBrowserScreen extends Screen {

    private static final int ROW_HEIGHT = 26;
    private static final int LIST_TOP_MARGIN = 62;
    private static final int LIST_BOTTOM_MARGIN = 16;
    private static final int LIST_SIDE_MARGIN = 60;
    private static final int MAX_LIST_WIDTH = 420;

    // Texts that never change are built once, not on every frame.
    private static final Text TITLE_TEXT = Text.literal("Current Auction Browser").formatted(Formatting.BOLD, Formatting.BLUE);
    private static final Text HINT_LIST = Text.literal("Click an item to set it up  |  Scroll  |  Type to search  |  Esc to close")
            .formatted(Formatting.DARK_GRAY);
    private static final Text HINT_SETUP = Text.literal("Set the minimum bid and time, then press Start Auction  |  Esc to close")
            .formatted(Formatting.DARK_GRAY);
    private static final Text NO_MATCH_TEXT = Text.literal("No items match your search.").formatted(Formatting.GRAY);
    private static final Text MIN_BID_LABEL = Text.literal("Minimum bid").formatted(Formatting.GRAY);
    private static final Text TIME_LABEL = Text.literal("Time  (plain number = minutes)").formatted(Formatting.GRAY);

    private static final int PANEL_WIDTH = 300;
    private static final int PANEL_HEIGHT = 228;
    private static final int PANEL_TOP = 70;

    private TextFieldWidget searchField;
    private TextFieldWidget minBidField;
    private TextFieldWidget timeField;
    private final List<ButtonWidget> setupButtons = new ArrayList<>();

    private String searchQuery = "";
    private String minBidText = "";
    private String timeText = "5m";
    private String errorMessage = "";
    private Text errorText = null;            // built when the error changes
    private Text selectedNameText = null;     // built when an item is picked
    private Text selectedSuggestText = null;
    private Text subtitleText = Text.empty();  // rebuilt only when the auction changes
    private long subtitleRev = -1;
    private boolean subtitleActive = false;
    private int previewSecs = -1;   // parsed once per edit, not per frame
    private String previewClock = "--:--";

    private double scrollOffset = 0;
    private List<AuctionItem> filteredItems = new ArrayList<>();
    private AuctionItem selectedItem = null;
    private boolean setupMode = false;

    public AuctionBrowserScreen() {
        super(Text.literal("Auction Browser"));
    }

    @Override
    protected void init() {
        setupButtons.clear();

        // ---- Search field (list mode) ----
        int fieldWidth = Math.min(320, this.width - 80);
        this.searchField = new TextFieldWidget(
                this.textRenderer,
                this.width / 2 - fieldWidth / 2,
                34,
                fieldWidth,
                20,
                Text.literal("Search")
        );
        this.searchField.setPlaceholder(Text.literal("Search items...").formatted(Formatting.GRAY));
        this.searchField.setMaxLength(64);
        this.searchField.setText(searchQuery);
        this.searchField.setChangedListener(this::onSearchChanged);
        this.addDrawableChild(this.searchField);

        // ---- Setup panel widgets ----
        int panelLeft = this.width / 2 - PANEL_WIDTH / 2;
        int innerLeft = panelLeft + 20;
        int innerWidth = PANEL_WIDTH - 40;

        this.minBidField = new TextFieldWidget(
                this.textRenderer, innerLeft, PANEL_TOP + 62, innerWidth, 20, Text.literal("Minimum bid"));
        this.minBidField.setPlaceholder(Text.literal("e.g. 250k, 1.5m, 2b").formatted(Formatting.GRAY));
        this.minBidField.setMaxLength(24);
        this.minBidField.setText(minBidText);
        this.minBidField.setChangedListener(text -> this.minBidText = text);
        this.addDrawableChild(this.minBidField);

        this.timeField = new TextFieldWidget(
                this.textRenderer, innerLeft, PANEL_TOP + 104, innerWidth, 20, Text.literal("Time"));
        this.timeField.setPlaceholder(Text.literal("e.g. 5m, 90s, 1m30s").formatted(Formatting.GRAY));
        this.timeField.setMaxLength(16);
        this.timeField.setText(timeText);
        updatePreview();
        this.timeField.setChangedListener(text -> {
            this.timeText = text;
            updatePreview();
        });
        this.addDrawableChild(this.timeField);

        // Quick time presets (two rows of four).
        String[][] presetRows = {
                {"1m", "5m", "10m", "30m"},
                {"45s", "30s", "20s", "15s"}
        };
        int gap = 4;
        int presetWidth = (innerWidth - gap * 3) / 4;
        for (int row = 0; row < presetRows.length; row++) {
            for (int i = 0; i < presetRows[row].length; i++) {
                final String preset = presetRows[row][i];
                ButtonWidget button = ButtonWidget.builder(Text.literal(preset), b -> {
                            this.timeField.setText(preset);
                            setError("");
                        })
                        .dimensions(innerLeft + i * (presetWidth + gap), PANEL_TOP + 130 + row * 22, presetWidth, 18)
                        .build();
                setupButtons.add(button);
                this.addDrawableChild(button);
            }
        }

        int bottomButtonWidth = (innerWidth - gap) / 2;
        ButtonWidget backButton = ButtonWidget.builder(Text.literal("Back"), b -> exitSetupMode())
                .dimensions(innerLeft, PANEL_TOP + 198, bottomButtonWidth, 20)
                .build();
        ButtonWidget startButton = ButtonWidget.builder(Text.literal("Start Auction"), b -> confirmStart())
                .dimensions(innerLeft + bottomButtonWidth + gap, PANEL_TOP + 198, bottomButtonWidth, 20)
                .build();
        setupButtons.add(backButton);
        setupButtons.add(startButton);
        this.addDrawableChild(backButton);
        this.addDrawableChild(startButton);

        refreshFilter();
        applyMode();
    }

    private void setError(String message) {
        this.errorMessage = message;
        this.errorText = message.isEmpty() ? null : Text.literal(message).formatted(Formatting.RED);
    }

    private void updatePreview() {
        previewSecs = TimeParse.seconds(timeText);
        previewClock = previewSecs > 0 ? TimeParse.clock(previewSecs) : "--:--";
    }

    private void onSearchChanged(String text) {
        this.searchQuery = text;
        refreshFilter();
    }

    private void refreshFilter() {
        String query = searchQuery.toLowerCase(Locale.ROOT).trim();
        List<AuctionItem> all = AuctionState.INSTANCE.getItems();
        if (query.isEmpty()) {
            this.filteredItems = all;
        } else {
            List<AuctionItem> out = new ArrayList<>();
            for (AuctionItem item : all) {
                if (item.searchKey().contains(query)) out.add(item);
            }
            this.filteredItems = out;
        }
        this.scrollOffset = 0;
    }

    // ------------------------------------------------------------
    // Mode switching
    // ------------------------------------------------------------

    private void enterSetupMode(AuctionItem item) {
        this.selectedItem = item;
        this.minBidText = Money.format(item.startingBid());
        this.timeText = "5m";
        setError("");
        this.selectedNameText = Text.literal(item.displayName()).formatted(Formatting.BOLD);
        this.selectedSuggestText = Text.literal("Suggested start: " + item.priceText()).formatted(Formatting.DARK_GRAY);
        this.minBidField.setText(minBidText);
        this.timeField.setText(timeText);
        this.setupMode = true;
        applyMode();
        this.setFocused(this.minBidField);
    }

    private void exitSetupMode() {
        this.setupMode = false;
        setError("");
        applyMode();
        this.setFocused(this.searchField);
    }

    private void applyMode() {
        this.searchField.setVisible(!setupMode);
        this.searchField.active = !setupMode;

        this.minBidField.setVisible(setupMode);
        this.minBidField.active = setupMode;
        this.timeField.setVisible(setupMode);
        this.timeField.active = setupMode;
        for (ButtonWidget button : setupButtons) {
            button.visible = setupMode;
            button.active = setupMode;
        }

        if (!setupMode) {
            this.setFocused(this.searchField);
        }
    }

    // ------------------------------------------------------------
    // Starting the auction
    // ------------------------------------------------------------

    private void confirmStart() {
        if (selectedItem == null) {
            return;
        }

        long minBid;
        try {
            minBid = Money.parse(minBidText);
        } catch (NumberFormatException e) {
            minBid = -1;
        }
        if (minBid <= 0) {
            setError("Enter a valid minimum bid (e.g. 250k, 1.5m).");
            return;
        }

        int seconds = TimeParse.seconds(timeText);
        if (seconds <= 0) {
            setError("Enter a valid time (e.g. 5m, 90s, 1m30s).");
            return;
        }

        AuctionState.INSTANCE.start(selectedItem.displayName(), selectedItem.item(), minBid, seconds);
        Actions.announceAuctionStart(selectedItem.displayName(), minBid, seconds);
        this.close();
    }

    // ------------------------------------------------------------
    // Layout helpers
    // ------------------------------------------------------------

    private int listLeft() {
        return this.width / 2 - Math.min(MAX_LIST_WIDTH, this.width - LIST_SIDE_MARGIN * 2) / 2;
    }

    private int listWidth() {
        return Math.min(MAX_LIST_WIDTH, this.width - LIST_SIDE_MARGIN * 2);
    }

    private int listTop() {
        return LIST_TOP_MARGIN;
    }

    private int listBottom() {
        return this.height - LIST_BOTTOM_MARGIN;
    }

    // ------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        // Dim the game world behind the screen.
        this.renderBackground(context, mouseX, mouseY, delta);

        // Header.
        context.drawCenteredTextWithShadow(this.textRenderer, TITLE_TEXT, this.width / 2, 12, 0xFFFFFFFF);
        int titleHalf = 64;
        context.fill(this.width / 2 - titleHalf, 22, this.width / 2 + titleHalf, 23, 0x663B82F6);
        context.fill(this.width / 2 - titleHalf / 2, 22, this.width / 2 + titleHalf / 2, 23, 0xFF3B82F6);

        AuctionState state = AuctionState.INSTANCE;
        boolean running = state.isActive();     // may end the auction, so read the revision after it
        long rev = state.revision();
        if (rev != subtitleRev || running != subtitleActive) {
            subtitleRev = rev;
            subtitleActive = running;
            String subtitle = !running
                    ? "No auction running  |  pick an item below to start one"
                    : "Current item: " + state.getCurrentItemName()
                    + "   |   Top bid: $" + Money.format(state.getHighestBid())
                    + " by " + state.getHighestBidder();
            subtitleText = Text.literal(subtitle).formatted(Formatting.GRAY);
        }
        context.drawCenteredTextWithShadow(this.textRenderer, subtitleText, this.width / 2, 26, 0xFFAAAAAA);

        if (setupMode) {
            renderSetupPanel(context);
        } else {
            renderList(context, mouseX, mouseY);
        }

        // Footer hint.
        context.drawCenteredTextWithShadow(this.textRenderer, setupMode ? HINT_SETUP : HINT_LIST,
                this.width / 2, this.height - 12, 0xFF777777);

        // Draws the visible text fields / buttons.
        super.render(context, mouseX, mouseY, delta);
    }

    private void renderSetupPanel(DrawContext context) {
        int panelLeft = this.width / 2 - PANEL_WIDTH / 2;
        int innerLeft = panelLeft + 20;

        context.fill(panelLeft + 3, PANEL_TOP + PANEL_HEIGHT, panelLeft + PANEL_WIDTH + 2, PANEL_TOP + PANEL_HEIGHT + 3, 0x55000000);   // shadow
        context.fill(panelLeft + PANEL_WIDTH, PANEL_TOP + 3, panelLeft + PANEL_WIDTH + 3, PANEL_TOP + PANEL_HEIGHT + 3, 0x55000000);
        Ui.box(context, panelLeft, PANEL_TOP, PANEL_WIDTH, PANEL_HEIGHT, 0xF00D1117, 0xFF30363D);
        Ui.vGradient(context, panelLeft + 1, PANEL_TOP + 1, PANEL_WIDTH - 2, 40, 0x1E3B82F6, 0x003B82F6);
        context.fill(panelLeft + 1, PANEL_TOP + 1, panelLeft + PANEL_WIDTH - 1, PANEL_TOP + 2, 0xFF3B82F6);

        // live countdown preview of the time you typed (parsed once per edit)
        int rcx = panelLeft + PANEL_WIDTH - 36, rcy = PANEL_TOP + 26;
        Ui.ring(context, rcx, rcy, 16, 13, previewSecs > 0 ? 1.0 : 0.0, 0xFF55FF7F, 0x26FFFFFF);
        String pv = previewClock;
        if (this.textRenderer.getWidth(pv) <= 24) {
            context.drawTextWithShadow(this.textRenderer, pv, rcx - this.textRenderer.getWidth(pv) / 2, rcy - 4,
                    previewSecs > 0 ? 0xFF55FF7F : 0xFF8B949E);
        }

        if (selectedItem != null && selectedNameText != null) {
            Ui.roundRect(context, innerLeft - 2, PANEL_TOP + 10, 20, 20, 0x30FFFFFF);
            context.drawItem(selectedItem.stack(), innerLeft, PANEL_TOP + 12);
            context.drawText(this.textRenderer, selectedNameText, innerLeft + 24, PANEL_TOP + 12, 0xFFFFFFFF, true);
            context.drawText(this.textRenderer, selectedSuggestText, innerLeft + 24, PANEL_TOP + 24, 0xFF888888, false);
        }

        context.drawText(this.textRenderer, MIN_BID_LABEL, innerLeft, PANEL_TOP + 51, 0xFFAAAAAA, false);
        context.drawText(this.textRenderer, TIME_LABEL, innerLeft, PANEL_TOP + 93, 0xFFAAAAAA, false);

        if (errorText != null) {
            context.drawText(this.textRenderer, errorText, innerLeft, PANEL_TOP + 178, 0xFFFF5555, false);
        }
    }

    private void renderList(DrawContext context, int mouseX, int mouseY) {
        int left = listLeft();
        int width = listWidth();
        int top = listTop();
        int bottom = listBottom();

        // List panel background + border.
        context.fill(left + 3, bottom, left + width + 2, bottom + 2, 0x55000000);   // shadow
        context.fill(left + width, top + 3, left + width + 2, bottom + 2, 0x55000000);
        Ui.box(context, left, top, width, bottom - top, 0xD00D1117, 0xFF30363D);

        // Clip rendering to the list area so rows don't bleed outside it.
        context.enableScissor(left + 1, top + 1, left + width - 1, bottom - 1);

        // Work out which rows are visible instead of looping over the whole list every frame.
        int base = top + 4 - (int) scrollOffset;
        int size = filteredItems.size();
        int first = Math.max(0, Math.floorDiv(top - base, ROW_HEIGHT) - 1);
        int last = Math.min(size - 1, Math.floorDiv(bottom - base, ROW_HEIGHT) + 1);
        for (int i = first; i <= last; i++) {
            int rowY = base + i * ROW_HEIGHT;
            if (rowY + ROW_HEIGHT >= top && rowY <= bottom) {
                renderRow(context, filteredItems.get(i), left + 4, rowY, width - 8, mouseX, mouseY);
            }
        }

        context.disableScissor();

        if (filteredItems.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, NO_MATCH_TEXT, this.width / 2, top + 20, 0xFF888888);
        }

        drawScrollbar(context, left, width, top, bottom);
    }

    private void renderRow(DrawContext context, AuctionItem item, int x, int y, int width, int mouseX, int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + ROW_HEIGHT - 2;
        boolean selected = item == selectedItem;

        int bg;
        if (selected) {
            bg = 0x803B82F6;
        } else if (hovered) {
            bg = 0x50FFFFFF;
        } else {
            bg = 0x1CFFFFFF;
        }
        Ui.roundRect(context, x, y, width, ROW_HEIGHT - 2, bg);
        if (selected || hovered) {
            context.fill(x, y + 2, x + 2, y + ROW_HEIGHT - 4, selected ? 0xFF3B82F6 : 0xFF8B949E);   // accent edge
        }

        // Item icon.
        context.drawItem(item.stack(), x + 4, y + 3);

        // Name.
        context.drawTextWithShadow(this.textRenderer, item.displayName(), x + 26, y + 3, 0xFFFFFFFF);

        // Starting bid, right-aligned.
        String priceStr = item.priceText();
        int priceWidth = this.textRenderer.getWidth(priceStr);
        Ui.roundRect(context, x + width - priceWidth - 16, y + 1, priceWidth + 12, 11, 0x2255FF7F);
        context.drawTextWithShadow(this.textRenderer, priceStr, x + width - priceWidth - 10, y + 3, 0xFF55FF7F);

        // Small "starting bid" caption under the name.
        context.drawTextWithShadow(this.textRenderer, "Starting bid", x + 26, y + 14, 0xFF777777);
    }

    private void drawScrollbar(DrawContext context, int left, int width, int top, int bottom) {
        int contentHeight = filteredItems.size() * ROW_HEIGHT;
        int visibleHeight = bottom - top;
        if (contentHeight <= visibleHeight) {
            return;
        }

        int trackX = left + width - 6;
        int trackHeight = visibleHeight - 8;
        int trackY = top + 4;

        Ui.roundRect(context, trackX, trackY, 4, trackHeight, 0x30FFFFFF);

        double maxScroll = contentHeight - visibleHeight;
        double scrollFraction = MathHelper.clamp(scrollOffset / maxScroll, 0, 1);
        int thumbHeight = Math.max(16, (int) ((double) visibleHeight / contentHeight * trackHeight));
        int thumbY = trackY + (int) ((trackHeight - thumbHeight) * scrollFraction);

        Ui.roundRect(context, trackX, thumbY, 4, thumbHeight, 0xFF3B82F6);
    }

    // ------------------------------------------------------------
    // Input
    // ------------------------------------------------------------

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (setupMode) {
            return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
        }
        int contentHeight = filteredItems.size() * ROW_HEIGHT;
        int visibleHeight = listBottom() - listTop();
        double maxScroll = Math.max(0, contentHeight - visibleHeight);
        scrollOffset = MathHelper.clamp(scrollOffset - verticalAmount * (ROW_HEIGHT * 1.5), 0, maxScroll);
        return true;
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (setupMode) {
            // Text fields and buttons handle their own clicks.
            return super.mouseClicked(click, doubled);
        }

        double mouseX = click.x();
        double mouseY = click.y();

        int left = listLeft();
        int width = listWidth();
        int top = listTop();
        int bottom = listBottom();

        if (mouseX >= left && mouseX < left + width && mouseY >= top && mouseY < bottom) {
            int relativeY = (int) (mouseY - top - 4 + scrollOffset);
            int index = relativeY / ROW_HEIGHT;
            if (index >= 0 && index < filteredItems.size()) {
                enterSetupMode(filteredItems.get(index));
                return true;
            }
        }

        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean shouldPause() {
        // Never pause the game (relevant in singleplayer/LAN testing).
        return false;
    }
}
