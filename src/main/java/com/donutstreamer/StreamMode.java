package com.donutstreamer;

import net.minecraft.client.gui.hud.debug.DebugHudEntries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.regex.Pattern;

/**
 * Stream-safety helpers: decides what the F3 screen may show and blanks coordinates in incoming chat.
 * Everything here is display-only on your own screen; nothing is sent anywhere.
 */
public final class StreamMode {
    private StreamMode() {}

    // "x: 120 y: 64 z: -300", "x=120, y=64, z=-300"
    private static final Pattern LABELLED = Pattern.compile(
            "(?i)\\bx\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?[,\\s]+y\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?[,\\s]+z\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?");
    // "120, 64, -300" (comma then a space; money like $1,234,567 has no spaces so it is left alone)
    private static final Pattern TRIPLE = Pattern.compile(
            "(?<![\\d$,.])-?\\d{1,6}(?:\\.\\d+)?,\\s+-?\\d{1,4}(?:\\.\\d+)?,\\s+-?\\d{1,6}(?:\\.\\d+)?(?![\\d,])");

    public static boolean hidingCoords() {
        Session s = DonutStreamerClient.session;
        return s != null && s.streamMode && s.streamHideCoords;
    }

    /** True for the F3 entries that reveal where you are (position, chunk/section, targeted block or fluid). */
    public static boolean isLocationEntry(Identifier id) {
        return DebugHudEntries.PLAYER_POSITION.equals(id)
                || DebugHudEntries.PLAYER_SECTION_POSITION.equals(id)
                || DebugHudEntries.LOOKING_AT_BLOCK.equals(id)
                || DebugHudEntries.LOOKING_AT_FLUID.equals(id);
    }

    /** MODIFY_GAME listener: blanks coordinates in chat when the option is on. */
    public static Text modifyChat(Text message, boolean overlay) {
        Session s = DonutStreamerClient.session;
        if (s == null || !s.streamMode || !s.streamHideChatCoords || overlay) return message;
        String plain = message.getString();
        // cheap pre-check: coordinates need a digit and a comma or colon/equals
        boolean hasDigit = false;
        for (int i = 0; i < plain.length(); i++) {
            if (Character.isDigit(plain.charAt(i))) { hasDigit = true; break; }
        }
        if (!hasDigit) return message;
        String out = LABELLED.matcher(plain).replaceAll("[coords hidden]");
        out = TRIPLE.matcher(out).replaceAll("[coords hidden]");
        if (out.equals(plain)) return message;
        // Rebuilt as plain text with the first style of the original (colours inside the line are simplified).
        return Text.literal(out).setStyle(message.getStyle());
    }
}
