# WebDisplays (Fabric)

In-game web browser screens for Minecraft 1.20.1 (Fabric). Build a wall, floor or ceiling of screen blocks, turn
it into a display, and browse the web on it — alone, in sync with everyone, or streamed live from one player.

Requires **Fabric API** and **MCEF** (the Chromium browser used to draw the pages). Every player and the server need
the same WebDisplays version.

---

## Quick start

1. Place **Screen** blocks in a rectangle (a wall, floor or ceiling).
2. Hold the **Screen Configurator** and right-click the block you want as the display's **bottom-left corner**.
   The display grows **to your right and upward** from there (on floors/ceilings, "up" is the way you are facing).
3. Enter the size in blocks and press **Create**. You are now the display's **owner**.
4. Hold a **Mouse** to point at and click the page. Use a **Keyboard** to type, enter URLs and manage tabs.

---

## Items

| Item | What it does |
|---|---|
| **Screen** | The block displays are built from. Each face of a block can hold its own display. |
| **Screen Configurator** | Right-click any part of a display to open its settings (or create a new display on free screen blocks). Using it on a display whose owner is gone/offline makes you the new owner. |
| **Mouse** | **Hold it (either hand) to point at and click displays.** Left-click = left mouse button, right-click = right mouse button. Without a mouse you cannot click pages. It is also the **Hybrid remote** (see below). |
| **Keyboard** | Right-click while looking at a display to open **keyboard input**: type into the page, address bar, back/forward, tabs and bookmarks. Sneak + right-click on the ground to place a two-block **keyboard** you can link to a display. In Hybrid mode you also need the display's linked Mouse (its remote) somewhere in your inventory. |
| **Linker** | Right-click a display, then right-click a placed keyboard to connect them. Using that keyboard then always types on the linked display. |

### Other controls

| Input | Effect |
|---|---|
| Shift + scroll wheel (with Mouse, pointing at a display) | Scroll the page |
| Ctrl + scroll wheel, or Ctrl + `+` / `-` / `0` | Zoom the page in / out / reset |
| F7 | Reset cursor tracking if it ever gets stuck |
| F6 | Hide/show display rendering on your client |

You can never break blocks through a display. To remove screen blocks, delete the display in its config first, or
break them from the back or sides.

---

## Display modes

Change the mode in the Screen Configurator with the **Mode** button (cycles **Sync → Solo → Hybrid**). Only the
owner can change modes.

### Sync (default) — everyone browses the same page

* Every player runs their **own browser** for the display, and WebDisplays keeps them in step: page navigation,
  tabs and video play/pause/seek are shared through the server.
* Pages are loaded separately on each computer, so logged-in content (accounts, personalised pages) may look
  different per player, and some sites may differ slightly.
* Everyone holding a Mouse sees each other's cursors (each player has their own cursor colour).

### Solo — everyone browses on their own

* Each player uses the display as a **private browser**. Nothing is synced: your navigation, tabs and videos only
  change your view.
* Solo is never restricted — anyone can use the display regardless of permissions.
* Cursors are not shared (everyone is looking at a different page).

### Hybrid — one player's browser is streamed to everyone

* The **owner's browser is the only real browser**. It is streamed live (WebRTC video) to every other player, so
  everyone sees exactly the same picture, including logged-in content, and nobody else loads the site.
* Viewers' extra tabs are closed while Hybrid is on; their display just shows the stream. Switching back to Sync
  restores the shared tabs.
* **Mouse control** belongs to whoever holds the display's **linked Mouse (the remote)** — the owner included. Only
  one person can hold it, so there is never more than one pointer fighting over the page. Viewers' clicks and
  scrolls are sent to the owner's browser.
* **Keyboard and tabs** need the remote too, but only **in your inventory** (it doesn't have to be in your hand):
  only the player carrying the linked Mouse can type, navigate or open/close tabs — the owner included. A viewer's
  typing, navigation and tab changes go to the owner's browser, so they change the page for everyone. (Viewers
  can't see the owner's tab list, so they can open and close tabs but not pick one.)
* Everyone sees the cursor of the person holding the remote.
* If the owner leaves the server, the display switches back to **Sync** automatically (there is nobody left to
  stream). Players who join late automatically connect to the stream.

#### Linking the Hybrid remote

1. As the owner, right-click the display with a Mouse. That mouse becomes the display's remote.
2. A display has **one** remote. To use a different mouse, first press **Unlink Remote** in the display config.
3. A linked mouse can't be linked to another display until it is unlinked from its current one.
4. After unlinking, the old mouse drops its link automatically (its tooltip stops saying it is a remote) the next
   time it is in a player's inventory near its display.

---

## Display settings (Screen Configurator)

| Setting | What it does | Modes |
|---|---|---|
| **Size: Auto / Manual** | *Auto*: the display grows/shrinks to fill the free screen blocks to its right and above its bottom-left corner. *Manual*: use the size fields. | All |
| **Blocks W × H** | Display size in blocks (picture width × height as you see it). Press **Apply**. | All |
| **Resolution: Auto / Manual** | *Auto*: 320 px per block. *Manual*: set the page width; the height follows the display's shape. | All |
| **Mode** | Sync / Solo / Hybrid (owner only). | All |
| **Others: Control / View only** | Whether other players may click, type and navigate, or only watch. The owner is never restricted. Solo ignores it; in Hybrid the remote decides instead (the button is replaced by Unlink Remote). | Sync |
| **Unlink Remote** | Removes the display's linked Mouse so another one can be linked (owner only). | Hybrid |
| **Rotation 0° / 90° / 180° / 270°** | Rotates the picture on the display. | All |
| **Page scale − / 100% / +** | Zooms the web page. | All |
| **Remove Display** | Deletes the display (the screen blocks stay). | All |

### Ownership

* Whoever creates a display owns it. Only the owner can change its mode, permissions and remote.
* When the owner leaves the server, the display becomes ownerless; the next player to use a Screen Configurator on
  it becomes the owner.

---

## Server notes

* Everything, including Hybrid's connection setup, runs through the normal Minecraft connection — **only the game
  port (25565) needs to be reachable**, so tunnels (playit, localtonet, ngrok …) work.
* The Hybrid **video** itself goes directly from the owner's PC to each viewer (peer-to-peer, using Google's public
  STUN server to get through home routers). On very strict networks (some mobile/carrier connections) the stream
  may fail to connect; viewers then stay on "Waiting for screen share".
* `config/webdisplays-hybrid.properties` controls a small local HTTP service left over from an older streaming
  method. It is not needed any more; set `enabled=false` to turn it off.

---

## Building

```
./gradlew build
```

The mod jar is written to `build/libs/`. Only the Windows WebRTC native library is bundled by default (it is ~20 MB
per platform); add more with
`./gradlew build -Pwebrtc_natives=windows-x86_64,linux-x86_64,macos-aarch64,macos-x86_64`.
