# Changelog

## 1.2.0
- **Settings screen**: `/ds settings` (or the new Open Settings keybind, default **O**, or the HUD editor's first row). Tabs for HUD, Elements, Chat, Music, Stream and Tracker; every change applies live and is saved.
- **Per-element customisation**: every HUD element now has its own size (0.4x to 4x, on top of the overall size), background opacity and accent colour. Pick one in Settings > Elements, or right-drag up/down over it in the chat-screen HUD editor. Dragging now snaps to the screen edges and centre (hold Shift to move freely).
- **Stream chat shows full messages**: long messages are word-wrapped instead of cut off with "...", and the 160-character cap is lifted (Twitch's own limit of 500 applies). The panel shows as many of the newest messages as fit.
- **Stream chat options**: width, height, lines per message (or full), dim-after time (or never), line spacing, message text colour, header bar, platform marker, viewer name colours, text shadow.
- **Music now works off YouTube / YouTube Music, no Premium or login.** A small browser userscript (written by `/ds music setup`) reports the playing track to the mod over 127.0.0.1 and takes play/pause/next/previous/volume commands back. It **mutes the tab while an ad plays** and restores the sound afterwards (`/ds music muteads` to switch off). The panel shows an AD - MUTED state. The Spotify integration was removed.
- Music panel options: cover art and equalizer can be hidden.
- New music commands: `/ds music setup|mute|muteads|port <n>`; `login` and `logout` are gone.

## 1.1.0
- New session every day at local midnight (also when you launch on a new day). The finished session is summarised in chat and in `donutstreamer/sessions.log`. Toggle with `/ds daily`.
- **New session** button in the `/ds` window (two clicks to confirm); `/ds new` does the same from chat.
- Option to hide the auction / Spotify preview panels in the chat screen when they have nothing to show: `/ds preview`.
- Option for a separate 12-second stream-delay countdown after each auction's main countdown (bids still count): `/ds auction delay`.
- Own nametag in F5 now uses your tab-list name when it carries your server rank (`/ds nametag rank`). Adds a third fail-soft mixin.
- Performance: saving no longer serialises your whole history on the game thread (it is written on the background thread); amount formatting no longer uses String.format or regex; HUD elements that don't show the clock no longer rebuild their text every second; the auction browser only walks the rows on screen and builds its texts once; the /ds window builds its text only when something changes.

## 1.0.0
- Stream mode (`/ds stream`): hides F3 coordinates (position, chunk, targeted block/fluid), optionally blanks coordinates in incoming chat, and hides player names on the HUD.
- Own nametag in third person (F5, `/ds nametag`), drawn through the game's normal nametag path so team prefixes and colours show like they do for other players.
- Optional on-screen Twitch and YouTube chat panel (`/ds chat ...`), read-only, with its own HUD element.
- Small blue "Sirens Streamer Mod" watermark, bottom-left, toggle with `/ds watermark`.
- Adds two small mixins (`donutstreamer.mixins.json`), set to fail soft: if a Minecraft update changes the target, that one feature turns itself off instead of crashing.
- UI polish pass: gradient HUD cards with a glowing accent edge and layered shadows, lit progress bars, tracker window with glassy stat cards and a live dot, HUD editor with toggle switches, auction browser with rounded rows, price pills and a live countdown-ring preview of the time you type.
- Auction panel: 12-tick countdown ring with a bright head dot, red pulsing border and flashing ring in the last 10s, item slot, and the top bid flashes white when someone outbids.
- Spotify panel: bigger glossy cover with a pulsing glow, cover-tinted card wash, 6-bar equalizer with peak caps, PLAYING/PAUSED tag, and a progress bar with a glowing knob.
- All new visuals are flat `fill` calls driven by the clock; text is still cached and nothing allocates per frame.
- Visual refresh: rounded cards with soft shadows, dim labels with bright values, rounded bars, a circular auction countdown, and a redesigned Spotify panel (cover art, equalizer, scrolling title, progress knob).
- Spotify remote: now-playing HUD panel plus play/pause/next/previous/volume from the game (`/ds music`, keybinds), via Spotify's Web API with PKCE sign-in. Client-side only; needs Spotify Premium.
- Merged the Donut Auction HUD into Donut Streamer: one mod, one config, one HUD editor.
- Auction panel is now a draggable, scalable HUD element; `/ds auction ...` commands (old `/auctionstart` and `/auctionstop` kept as aliases).
- Auction bids use the tracker's anchored chat pattern, so fake lines typed in public chat can't hijack an auction.
- Performance: background config/CSV writes, cached HUD text and chart data, chat pre-filter, item list built at startup, no per-frame allocation in the browser.
- Fixes: amount parser no longer accepts `5d`/`1e9`/`NaN`; `999999` formats as `1M` instead of `1000K`; CSV fields are quoted; corrupt configs are kept as `.corrupt` instead of overwritten; widgets can no longer be stranded off-screen.
- Added icon, license, `.gitignore`, and a release workflow.
