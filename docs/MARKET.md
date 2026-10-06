# Wallet, market, coin flips and leaderboard

All features are in the **MKSidebarRanks** jar. Paper 1.21.11 and Java 21 are required; no economy companion plugin, resource pack or client mod is needed. This is an in-game dollar currency with two decimal places. No real-money purchases or cash prizes are involved.

## First installation and upgrades

Stop the server, back up `plugins/MKSidebarRanks`, replace the old jar with `mk-sidebar-ranks-1.5.0.jar`, and restart. Keep the folder and install only one MKSidebarRanks jar. Existing rank/playtime data in `players.db` stays intact. New economy data lives in **`market.db`** in that same folder.

Every saved player record gets **$500 once** when the economy is initialized. Future players get $500 on first join. Reconnecting, changing display names, reloading, or restarting does not award it again. Changing `economy.starting-balance` affects future accounts. Identity is keyed to Minecraft UUID: changing authentication mode/UUID creates a different identity; names never silently merge wallets.

Older configs are backed up and missing economy settings are added. An unchanged default sidebar gains the Money row; custom sidebar lines remain intact. Add `<white>Money:</white> %player_balance%` manually to a custom layout if desired. `%player_balance%` is the formatted wallet balance in sidebar/Tab templates. Rank-event templates show a dash for this value because those events do not load economy data.

## Player commands

| Command | Purpose |
| --- | --- |
| `/money` or `/bal` | Your spendable wallet balance |
| `/money history [page]` | Ten recent transactions per page, including sale receipts |
| `/pay <player\|UUID> <amount>` | Transfer a positive amount to a saved player, including offline players |
| `/baltop` | Public top ten spendable balances, including offline wallets |
| `/auction` or `/ah` | Browse fixed-price listings |
| `/auction sell <price>` | List the entire stack in your main hand at this **total stack price** |
| `/auction mine` | View/cancel your active listings |
| `/auction cancel <ID>` | Cancel your own active listing and return its item to the mailbox |
| `/auction mail` | Claim purchases or returned items, and open transaction receipts |
| `/coinflip <player> <amount>` | Challenge an online player to match your wager |
| `/coinflip accept [ID]` | Match a pending wager and play the coin flip |
| `/coinflip decline [ID]` | Decline an incoming challenge |
| `/coinflip cancel [ID]` | Cancel your outgoing challenge |

The player permission is `mksidebar.market`, enabled for everyone by default. If another plugin owns one of these commands, the namespaced fallback is `/mksidebarranks:auction` (or the corresponding command). The wallet is internal to MKSidebarRanks; it is not a Vault provider.

## Buying and selling

Hold a stack, close other inventory menus, and use `/auction sell 125` to list it for **$125 total**. Listing removes the complete stack into escrow. Selling is allowed in Survival/Adventure mode. Existing custom names, enchantments, damage and item data are retained; menu lore is only added to the preview, not to the purchased item.

Listings last seven days by default, with five active/pending listings per player and no seller fee. The total market limit defaults to 5,000. Expired/cancelled items return to an offline-safe mailbox and never drop on the ground. Categories cycle through All, Blocks, Items, Food and Tools. Sort by newest, cheapest or most expensive; browse pages, your listings, mailbox and receipts using the bottom-row icons. Item categories can overlap (a pickaxe appears under both Items and Tools).

Click a listing, then confirm its price. Until confirmation no money moves. A successful purchase atomically debits the buyer, credits the seller (even offline), marks the listing sold, and places the exact item in the buyer's mailbox. A second buyer cannot buy that same listing. A stale confirmation, insufficient funds or failed database transaction leaves the purchase unchanged. Sale earnings go directly to the wallet; the mailbox's receipts button opens `/money history`.

A mailbox claim requires room for the complete stack. The inventory briefly locks during transfer, and its saved state is journalled before completion. Items do not partially deliver or spill onto the floor. Listing and mailbox inventory snapshots reconcile on reconnect after an interrupted transfer. If another plugin/admin changed that inventory and neither saved snapshot matches, the affected transfer is held for admin review rather than automatically replayed.

## Coin flips

`/coinflip Alex 50` reserves your $50. Alex gets clickable Accept/Decline choices and must match $50 when accepting. Each side has a 50% chance; the winner receives **$100**, with no fee. Outcomes use Java's secure random source. There is no solo bet against the server and no new currency is minted by the flip.

Only one challenge involving a player may be pending at a time. Default wagers are $1–$10,000; default expiry is 60 seconds. An opponent must be online and able to afford the initial challenge, and their funds are checked again on acceptance. Declining, cancelling, expiry, disconnect, or server restart/shutdown refunds the reserved stake. An unsuccessful acceptance retains the challenge for retry/decline until expiry. Wallet balances on `/baltop` reflect money available to spend and exclude reserved wagers.

## Administrator commands

These require `mksidebar.admin` (operators by default); console supports them except placement, which needs a player position.

| Command | Purpose |
| --- | --- |
| `/mkmarket money add <player\|UUID> <amount>` | Credit a saved wallet |
| `/mkmarket money remove <player\|UUID> <amount>` | Debit a saved wallet; cannot go below zero |
| `/mkmarket leaderboard place` | Place/replace the one floating top-ten board 2.5 blocks above your position |
| `/mkmarket leaderboard remove` | Remove the board and its saved location |
| `/mkmarket performance` | Recent main-thread market work and worst inventory-save duration since enable |
| `/mkmarket recovery` | List inventory transfers held for review |
| `/mkmarket recovery <ID> applied\|unapplied` | Resolve a reviewed transfer after checking inventory/escrow records |
| `/mksb reload` | Validate and reload all rank/display/market settings |

Admin money changes appear in the player's history and server log. Amounts support cents, reject negatives/exponential values and are capped. Current online sessions win name resolution; ambiguous offline saved names require a UUID, matching the existing rank commands.

The leaderboard uses one persistent text display, refreshes every 60 seconds by default, and only changes text when needed. It includes offline players, saves its location, and restores across restart. It follows normal entity visibility; the plugin does not force-load its chunk. Use `money-leaderboard.enabled: false` to hide it while retaining the saved location. Place a new board to relocate the existing one.

### Recovery terminology

Take a stopped-server backup before manually resolving ambiguous records. `applied` means the recorded inventory change happened: a LIST operation removed the seller's stack, or a CLAIM operation delivered the mailbox stack. Resolving LIST as applied activates the existing escrow listing; resolving CLAIM as applied marks the item delivered. `unapplied` cancels a pending LIST (the original stack is still in the player inventory) or returns a pending CLAIM to ready status. Never choose unapplied for a claim whose item was already delivered elsewhere; that would create a duplicate. If unsure, leave it held and inspect the saved operation's before/after inventory snapshots with the administrator who knows the player's inventory history.

## Settings and performance

Settings are under `economy`, `auction`, `coinflip`, `money-leaderboard`, and `market-reminders` in `config.yml`. Chat reminders default to two minutes and mention both `/auction` and `/coinflip`; their text, enable switch and interval are configurable. Reminders are only sent while players are online. Failed validation keeps the running settings. Configurable fees default to zero; if enabled they are deducted from seller proceeds, with cents rounded down for the fee.

Database writes and balance/leaderboard queries run on a dedicated SQLite worker with durable transactions. The sidebar reads cached balances; it never queries SQLite per tick. Market menus build only when requested. Timed work expires listings/challenges and refreshes the board, not every player's inventory. Critical item handoffs also call Paper's player-inventory save on the main thread to support recovery; at most one such handoff completes per tick so concurrent listings/claims cannot stack their disk writes into one tick. The disk write is measured separately by `/mkmarket performance`, and can vary with storage speed. Inventory locking does not make players immune to damage. The transaction history is retained; monitor disk use and back up the entire plugin folder with the server stopped.

See [PERFORMANCE.md](PERFORMANCE.md) for measured limits. No zero-lag guarantee is implied.
