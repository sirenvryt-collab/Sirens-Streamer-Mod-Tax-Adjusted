package com.donutstreamer.chat;

/** One line of stream chat. Layout strings are filled lazily on the render thread (text widths need the font). */
final class ChatMessage {
    static final int TWITCH = 0, YOUTUBE = 1;

    final int platform;
    final String name;
    final String text;
    final int color;          // ARGB name colour
    final long time;

    // render-thread cache (rebuilt when the panel width or the chat options change)
    String nameShown;
    String[] lines;           // the message wrapped to the panel width; lines[0] sits next to the name
    int nameWidth;
    int laidOutFor = -1;      // key of the options the cache was built for

    ChatMessage(int platform, String name, String text, int color) {
        this.platform = platform;
        this.name = name;
        this.text = text;
        this.color = color;
        this.time = System.currentTimeMillis();
    }
}
