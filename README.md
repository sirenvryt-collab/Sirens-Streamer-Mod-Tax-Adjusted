# Donut Streamer

A client-side Fabric mod for **Minecraft 1.21.11** that gives DonutSMP streamers three tools in one jar:

1. **Session tracker**: reads payment messages from chat and keeps your profit, record, streaks and charts
   up to date automatically, with a fully draggable stream HUD (in the style of Gambler Plus). Everything is customisable in `/ds settings`.
2. **Auction tools**: a live auction panel (countdown, top bidder, top bid) and a searchable item browser,
   fed by the same chat messages.
3. **Music remote**: a now-playing HUD panel and play/pause/skip/volume controls for YouTube / YouTube Music in your browser (no Premium), with automatic muting of ads.

No hotkeys to press for the tracker, nothing to click. The mod only reads chat you can already see.

Plus stream tools: a **stream mode** that hides your coordinates, your own **nametag in F5**, optional **Twitch / YouTube chat** on screen, and a small blue **"Sirens Streamer Mod"** tag you can turn off.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for 1.21.11 (0.18.1 or newer) and Java 21.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and `donutstreamer-<version>.jar` in your `mods` folder.

## Tracker

| Chat message | Meaning |
|---|---|
| `name paid you $ amount` | money received (counts as a win) |
| `You paid name $ amount` | money sent (counts as a loss) |
| `You bought N item for $ amount` | subtracts from live profit, not a gamble |
| `$ amount` on its own (selling) | sale income, tracked separately |

Payments under **10M** are ignored by the tracker. The space after `$` is optional.
Net profit = received - sent - bought. Sales are shown separately; `/ds sells` adds them to net.
If your server shows sales in the action bar instead of chat, run `/ds sellsource`.
Lines must *start* with the pattern, so a player typing a fake line in public chat is ignored.

**Doubled payments.** If a player pays you and then pays you exactly double without you paying them in
between, the chat line gets a gold tag such as `[DOUBLED: 10K -> 20K]` (or `x2 in a row` for a chain).
Paying that player resets it. Toggle with `/ds doubles`.

**Tracker window.** `/ds` opens net profit, record, timer, charts and a per-player table
(what each player sent you, what you sent them, net, and rakeback owed if `/ds rakeback 5` is set).
The **New session** button (bottom right; click twice to confirm) starts a fresh session on demand.

**Daily sessions.** A new session starts automatically at local midnight (also if you launch the game on a new day). The finished session's summary is printed in chat and appended to `donutstreamer/sessions.log`. Turn it off with `/ds daily`.

**HUD.** Separate elements, each on/off and draggable: net profit, W-L record, streak, last payment,
timer, profit graph, result bars, win-rate bar, last-10 form, totals, averages, biggest in/out, peak/low,
best/worst streak, profit per gamble/hour, top payer, balance, goal and loss-limit bars.
Open chat (**T**), drag elements with the left mouse button (they snap to the edges and centre; hold Shift to move freely),
right-drag up/down over an element to resize just that one, and use the **HUD editor** button (top right) to toggle elements.
For sliders, opacity and colours per element use **`/ds settings`** > Elements. Elements that would end up off-screen after a resize are pulled back in.

**Alerts.** `/ds tilt 5` (warning after 5 payments sent in a row with nothing back), `/ds limit 500k`
(loss limit), `/ds goal 2m` (take-profit).

## Auctions (DonutSMP only)

Auction tools only run while connected to `donutsmp.net`.

- Press **B** to open the item browser: search, scroll, click an item, set the minimum bid and time, press
  **Start Auction**. Or use a command: `/ds auction start "Dragon Egg" 5m 10m` (item, minimum bid, time).
- The **auction panel** appears on screen while an auction runs, with a circular countdown ring that turns amber at 30s and flashes red in the last 10s. It is a normal HUD element, so you can drag it
  in the HUD editor, scale it, or hide it with **H**.
- While an auction runs, every `name paid you $ amount` at or above the minimum bid counts as a bid; the highest
  one is shown as top bidder. Bids can be smaller than the tracker's 10M minimum. When the timer ends, the
  winner is announced in your chat. The timer never extends once started, whatever happens.
- **Stream delay** (`/ds auction delay`, off by default): after the main countdown reaches zero, a separate purple 12-second countdown runs (the panel title changes to STREAM DELAY). Bids still count during it, so viewers watching on a stream delay can still bid. The winner is announced when it ends.
- `/ds auction track` decides whether payments that count as bids are also added to the gambling tracker
  (default: yes).

Times accept `90s`, `5m`, `1m30s`, `2h15m` (up to 24h). A plain number means minutes.
`/auctionstart` and `/auctionstop` still work as short aliases (a plain number in `/auctionstart` means seconds).

## Music (YouTube, no Premium)

Minecraft can't play or capture YouTube audio itself. Instead a small **userscript** runs in your browser tab, tells the
mod what is playing, and carries your controls back. Your YouTube / YouTube Music tab keeps playing the sound as usual.
It needs no account, no Premium and no API key.

**One-time setup:**

1. Install the **Tampermonkey** (or Violentmonkey) browser extension.
2. In game run `/ds music setup`. It writes `donutstreamer/youtube-music.user.js` in your game folder and prints the path.
3. Copy the file's contents into a new userscript in Tampermonkey and save. Reload your YouTube tab and play something.

The HUD panel (default bottom-right, draggable and resizable like every element) then shows the title, channel, a
progress bar and PLAYING / PAUSED. Controls: `/ds music play|pause|toggle|next|prev|volume <0-100>|mute|status`, the keybinds
**\\** (play/pause), **]** (next), **[** (previous), or the buttons in `/ds settings` > Music.

**Ads.** When YouTube plays an ad, the script mutes that tab and the panel shows **AD - MUTED**; the sound comes back
when the ad ends and your own mute state is restored. It only mutes: it never skips or blocks ads. Switch it off with
`/ds music muteads`. Ad detection follows YouTube's page, which changes now and then; if it ever stops working, the
script is plain text and `.ad-showing` in it is the thing to update.

Notes:
- If several YouTube tabs are open, the one that is playing is used.
- The mod only listens on `127.0.0.1` (this PC, nothing else) and ignores requests without the random token that is written
  into your own copy of the script. Don't share that file or `config/donutstreamer-music.json`.
- The default port is 8765. If something else uses it, `/ds music port <n>`, then run `/ds music setup` again and update the script.
- On stream, YouTube music can get VODs muted or claimed. Pause it or use stream-safe music.

## Stream tools

**Stream mode** (`/ds stream`) is a master switch. While it is on:

- **F3 coordinates are hidden**: the position, chunk/section and "targeted block / fluid" lines disappear from the F3 screen (`/ds stream coords` turns just this part on or off).
- **Coordinates in chat are blanked** (`/ds stream chatcoords`, off by default): lines like `x: 120 y: 64 z: -300` or `120, 64, -300` show `[coords hidden]`. It rebuilds the line as plain text, so colours inside that one line are simplified. Money like `$1,234,567` is left alone.
- **Player names are hidden on the HUD** (`/ds stream names`).

`/ds stream status` lists the current state. Everything is display-only on your own screen.

**Own nametag in F5** (`/ds nametag`, on by default): in third person your own nametag is drawn through the same path the game uses for other players, so team prefixes and colours (ranks, Media, etc.) look the way they do to everyone else. First person is unchanged. If your rank lives in the tab list rather than in a team prefix, the tag uses your tab-list name so the rank shows (`/ds nametag rank` toggles this).

**Watermark** (`/ds watermark`): a small blue "Sirens Streamer Mod" in the bottom-left corner. Also in the HUD editor.

### Stream chat (Twitch / YouTube)

An optional on-screen chat panel (a normal HUD element: `/ds hud chat`, draggable and scalable; width, height, wrapping and look are in `/ds settings` > Chat). It is read-only; the mod cannot post. Long messages are wrapped and shown in full.

- **Twitch**: `/ds chat twitch <channel>` (a twitch.tv link works too). Connects anonymously over Twitch's public chat server, so no account or token is needed. `/ds chat twitch off` disconnects.
- **YouTube**: needs your own free API key (YouTube Data API v3) and the live stream. Copy the key, run `/ds chat youtubekey paste` (pasting keeps the key off your screen), then `/ds chat youtube <link or video id>`. Each poll uses a few quota units of the 10,000 free per day, which is several hours of chat. The key is stored in `config/donutstreamer-chat.json`: don't share or commit it.
- `/ds chat` shows status, `/ds chat clear` empties the panel, `/ds chat off` disconnects both, and `/ds chat commands` toggles hiding of `!commands`.

Nothing connects until you run one of these commands. Your chat setup is remembered and reconnects at launch.

## Commands

| Command | |
|---|---|
| `/ds`, `/ds help` | tracker window, command list |
| `/ds in\|out <amt> [name]`, `/ds buy <amt> [item]`, `/ds sell <amt>` | add something the reader missed |
| `/ds undo`, `/ds reset` or `/ds new` (twice), `/ds summary`, `/ds players` | session tools (`summary` copies to clipboard) |
| `/ds daily` | toggle the automatic new session at midnight |
| `/ds preview` | toggle the auction / music preview panels in the chat screen when they have nothing to show |
| `/ds limit\|goal <amt\|off>`, `/ds tilt <2-20\|off>`, `/ds rakeback <0.5-50\|off>`, `/ds balance <amt\|off>` | alerts and numbers |
| `/ds percent`, `/ds sells`, `/ds sellsource`, `/ds doubles`, `/ds sound`, `/ds names` | toggles (`names` hides player names for stream) |
| `/ds hud toggle\|reset\|all on\|all off\|scale <0.5-3>\|<element>` | HUD layout |
| `/ds auction start "<item>" <min bid> [time]`, `stop`, `status`, `browser` | auctions |
| `/ds auction track\|anywhere\|delay` | count bids in tracker / allow auctions on any server (testing) / add 12s stream delay |
| `/ds stream [coords\|chatcoords\|names\|status]`, `/ds nametag [rank]`, `/ds watermark` | stream mode, own F5 nametag (and its rank), watermark |
| `/ds chat twitch <channel>\|off`, `/ds chat youtube <link>\|off`, `/ds chat youtubekey paste\|<key>\|clear`, `/ds chat status\|clear\|off\|commands` | Twitch / YouTube chat |
| `/ds settings` | settings screen (also keybind **O**) |
| `/ds music setup`, `play`, `pause`, `toggle`, `next`, `prev`, `volume <0-100>`, `mute`, `muteads`, `port <n>`, `status` | YouTube music remote |

Amounts accept `k`, `m`, `b`, `t` (for example `1.5m`). Keybinds (**B**, **H**) can be changed in Controls.

## Files

- `config/donutstreamer.json`: session and settings (saved in the background every few seconds and on exit)
- `config/donutstreamer-music.json`: the music bridge's port and private token (keep private)
- `<game dir>/donutstreamer/youtube-music.user.js`: the browser userscript written by `/ds music setup`
- `config/donutstreamer-chat.json`: your Twitch channel, YouTube video and YouTube API key (keep private)
- `<game dir>/donutstreamer/bets.csv`: every payment, for post-stream review
- `<game dir>/donutstreamer/sessions.log`: one summary line for every finished session

## Performance

Nothing touches the disk on the game thread, and saving never serialises your history there either (that happens on
the background writer). HUD text, chart data, the /ds window and the player table are cached and only rebuilt when
something changes (only the timer widgets update each second), amount formatting avoids String.format and regex, chat
lines without a `$` are skipped before any pattern matching, the item list for the browser is built once at startup,
and the browser only draws the rows that are on screen.

## Safety

No packets are sent to Minecraft servers by the mod, no automation, no input simulation, no world or inventory reads.
It reads chat you can already see, keeps numbers, and draws a HUD. The only network traffic is the music bridge (local only, 127.0.0.1) and the optional stream chat (Twitch's public chat server, or the YouTube Data API) after you set it up.

## Building

```
gradle build
```

needs JDK 21 and Gradle 9.x (or let GitHub Actions do it). The jar is `build/libs/donutstreamer-<version>.jar`
(not the `-sources` jar).

### On GitHub

The workflow lives at `.github/workflows/build.yml`. Some uploaders skip hidden folders, so if the **Actions** tab
shows no workflow, add that file by hand: *Add file > Create new file*, type `.github/workflows/build.yml` as the
name, and paste the contents.

- Every push builds the mod: download the `donutstreamer-jar` artifact from the run.
- Push a tag like `v1.0.0` and the workflow also publishes a GitHub Release with the jar attached.

## License

MIT, see `LICENSE`.
