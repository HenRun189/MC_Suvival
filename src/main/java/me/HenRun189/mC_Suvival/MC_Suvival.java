package me.HenRun189.mC_Suvival;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import com.mojang.brigadier.Command;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.event.entity.EntityEquipmentChangedEvent;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import me.HenRun189.mC_Suvival.economy.MoneyItem;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class MC_Suvival extends JavaPlugin implements Listener {

    // Start-zone center from the supplied coordinates. Change the world here if required.
    private static final String LAUNCH_WORLD = "world";
    private static final double LAUNCH_CENTER_X = -356.0;
    private static final double LAUNCH_CENTER_Y = 100.0; // Minimum Y at which a launch can start.
    private static final double LAUNCH_CENTER_Z = -1019.0;
    private static final double LAUNCH_RADIUS = 20.0;

    private static final double BOOST_HORIZONTAL_STRENGTH = 1.2;
    private static final double BOOST_VERTICAL_STRENGTH = 0.85;
    private static final int MAX_BOOSTS = 2;
    private static final boolean SEND_MESSAGES = true;

    /**
     * Ein gehaltener Klick wiederholt sich beim Vanilla-Client alle 4 Ticks
     * (~200 ms). Eingaenge innerhalb dieses Fensters gelten als derselbe
     * gedrueckte Klick und verbrauchen keinen weiteren Boost.
     */
    private static final long BOOST_HELD_INPUT_WINDOW_MS = 450L;

    // ===== Zustand =====
    private final Set<UUID> hasJumpedFromSpawn = new HashSet<>();
    private final Set<UUID> hasBeenAirborneFromSpawn = new HashSet<>();
    private final Set<UUID> hasElytraActive = new HashSet<>();
    private final Map<UUID, Integer> boostsLeft = new HashMap<>();
    private final Map<UUID, ItemStack> savedChestplates = new HashMap<>();
    private final Map<UUID, Long> lastBoostInputMs = new HashMap<>();

    // ===== PDC-Schluessel zum Markieren der temporaeren Elytra =====
    private NamespacedKey spawnElytraKey;

    /**
     * "Sky's the Limit": Dieses Vanilla-Advancement wird durch die technisch
     * echte Elytra ausgeloest und bei Spawn-Elytra-Fluegen sofort zurueckgesetzt.
     */
    private Advancement elytraAdvancement;

    /** Physische Server-Waehrung: erzeugt und identifiziert die Server Coins. */
    private MoneyItem moneyItem;

    @Override
    public void onEnable() {
        spawnElytraKey = new NamespacedKey(this, "spawn_elytra");
        elytraAdvancement = getServer().getAdvancement(NamespacedKey.minecraft("end/elytra"));
        if (elytraAdvancement == null) {
            getLogger().warning("Advancement minecraft:end/elytra wurde nicht gefunden - "
                    + "das Zuruecksetzen bei Spawn-Elytra-Fluegen ist deaktiviert.");
        }

        this.moneyItem = new MoneyItem(this);
        registerCommands();

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Launch zone: world=" + LAUNCH_WORLD + ", center=("
                + LAUNCH_CENTER_X + ", " + LAUNCH_CENTER_Y + ", " + LAUNCH_CENTER_Z
                + "), radius=" + LAUNCH_RADIUS);
    }

    // =====================================================================
    // Command /coin
    // =====================================================================

    /**
     * paper-plugin.yml unterstuetzt keine klassische commands-Sektion, daher
     * wird /coin ueber das Brigadier-Lifecycle-Event registriert (der offizielle
     * Weg fuer Paper 26.3). Das Event feuert auch nach /minecraft:reload erneut,
     * der Command bleibt also immer registriert.
     */
    private void registerCommands() {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        Commands.literal("coin")
                                .executes(context -> {
                                    if (!(context.getSource().getSender() instanceof Player player)) {
                                        context.getSource().getSender().sendRichMessage(
                                                "<red>Only players can use this command.</red>");
                                        return 0;
                                    }
                                    giveCoin(player);
                                    return Command.SINGLE_SUCCESS;
                                })
                                .build(),
                        "Gives you one Server Coin (value: 1).",
                        List.of()
                ));
    }

    /**
     * Gibt genau einen Server Coin. Bei vollem Inventar wird das Item sicher
     * am Boden gedroppt statt verworfen zu werden.
     */
    private void giveCoin(Player player) {
        HashMap<Integer, ItemStack> leftover = giveOrDrop(player, this.moneyItem.createCoin());
        if (leftover.isEmpty()) {
            player.sendRichMessage("<green>You received a Server Coin.</green>");
        } else {
            player.sendRichMessage("<green>You received a Server Coin.</green> "
                    + "<gray>Your inventory was full - it dropped at your feet.</gray>");
        }
    }

    @Override
    public void onDisable() {
        for (Player player : getServer().getOnlinePlayers()) {
            clearPlayerState(player, false);
        }
    }

    // =====================================================================
    // Launch-Zone
    // =====================================================================

    /** Launch zone is a vertical cylinder in the configured world, at or above the minimum Y. */
    private boolean isInLaunchArea(Location location) {
        if (location == null || location.getWorld() == null
                || !LAUNCH_WORLD.equalsIgnoreCase(location.getWorld().getName())
                || location.getY() < LAUNCH_CENTER_Y) {
            return false;
        }

        double dx = location.getX() - LAUNCH_CENTER_X;
        double dz = location.getZ() - LAUNCH_CENTER_Z;
        return dx * dx + dz * dz <= LAUNCH_RADIUS * LAUNCH_RADIUS;
    }

    /** Paper detects intentional jumps directly, without guessing from player movement. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJump(PlayerJumpEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!hasElytraActive.contains(uuid) && isInLaunchArea(event.getFrom())) {
            hasJumpedFromSpawn.add(uuid);
            // The jump event itself confirms that the player left the ground.
            hasBeenAirborneFromSpawn.add(uuid);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Location from = event.getFrom();
        Location to = event.getTo();

        if (hasElytraActive.contains(uuid)) {

            if (to.getWorld() == null
                    || !LAUNCH_WORLD.equalsIgnoreCase(to.getWorld().getName())) {

                clearPlayerState(player, true);
                return;
            }

            if (isOnGround(player)) {
                clearPlayerState(player, true);
                return;
            }

            // Solange der Spieler faellt, Gliding halten/aktivieren.
            if (!player.isGliding() && player.getVelocity().getY() <= 0.0) {
                player.setGliding(true);
            }

            return;
        }

        if (hasJumpedFromSpawn.contains(uuid)) {
            if (to.getWorld() == null || !LAUNCH_WORLD.equalsIgnoreCase(to.getWorld().getName())) {
                hasJumpedFromSpawn.remove(uuid);
                hasBeenAirborneFromSpawn.remove(uuid);
                return;
            }

            if (!isOnGround(player)) {
                hasBeenAirborneFromSpawn.add(uuid);
            } else if (hasBeenAirborneFromSpawn.remove(uuid)) {
                // The player landed before activating.
                hasJumpedFromSpawn.remove(uuid);
            }
            return;
        }

        // Edge-fall fallback: onGround can still be true on the first tick off a block,
        // so detect downward movement from the zone instead of relying on onGround alone.
        if (isInLaunchArea(from) && to.getY() < from.getY()) {
            hasJumpedFromSpawn.add(uuid);
            hasBeenAirborneFromSpawn.add(uuid);
        }
    }

    // =====================================================================
    // Aktivierung per Leertaste (Paper 1.21.3+ / 26.3: PlayerInputEvent)
    // =====================================================================

    /**
     * Bukkit/Paper hat kein klassisches Key-Event, aber Paper stellt seit 1.21.3
     * den aktuellen Eingabe-Zustand des Clients bereit: PlayerInputEvent wird
     * gefeuert, sobald der Spieler neue Eingaben sendet; Input.isJump() ist der
     * Leertasten-Input. Das fuehlt sich fuer den Spieler exakt wie
     * "Leertaste = Elytra-Start" an.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Nur aktivieren, wenn vorher aus der Launch-Zone gesprungen wurde,
        // die Elytra noch nicht aktiv ist und die Leertaste gedrueckt ist.
        if (!hasJumpedFromSpawn.contains(uuid)
                || hasElytraActive.contains(uuid)
                || !event.getInput().isJump()) {
            return;
        }
        if (isOnGround(player)) {
            return;
        }

        activateSpawnElytra(player);
    }

    // =====================================================================
    // Boost per Rechtsklick
    // =====================================================================

    /**
     * Rechtsklick in die Luft feuert nur, wenn irgendein Item in der Hand ist:
     * Ohne Item sendet der Vanilla-Client gar kein Use-Item-Paket (Paper #11256).
     * Haupt-Trigger ist daher der Rechtsklick mit beliebigem Item (nichts wird
     * verbraucht, das Event bleibt unangetastet). Rechtsklick mit Brust-Ruestung
     * wird blockiert, weil die Vanilla-Anzieh-Mechanik sonst die Spawn-Elytra
     * aus dem Chestplate-Slot verdraengen wuerde.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!hasElytraActive.contains(uuid)) {
            return;
        }

        boolean rightClick = event.getAction() == Action.RIGHT_CLICK_AIR
                || event.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!rightClick) {
            return;
        }

        ItemStack held = event.getItem();
        if (held != null && isChestArmor(held.getType())) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() == Action.RIGHT_CLICK_AIR) {
            tryBoost(player, uuid);
        }
    }

    /**
     * Fallback fuer komplett leere Haende: Rechtsklick ohne Item erzeugt kein
     * Paket, aber Linksklick (Arm-Swing) sendet der Vanilla-Client immer. Beide
     * Wege laufen ueber dasselbe Edge-Trigger-Gate in tryBoost().
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmSwing(PlayerArmSwingEvent event) {
        Player player = event.getPlayer();
        if (hasElytraActive.contains(player.getUniqueId())) {
            tryBoost(player, player.getUniqueId());
        }
    }

    /**
     * Edge-Trigger: Ein Boost nur beim Uebergang "nicht gedrueckt" ->
     * "gedrueckt". Der Vanilla-Client wiederholt einen gehaltenen Klick alle
     * 4 Ticks (~200 ms); solange innerhalb des Zeitfensters weitere Eingaenge
     * ankommen, gilt derselbe Klick als gehalten (rightClickWasPressed) und
     * verbraucht keinen weiteren Boost. Erst nach dem Loslassen und erneutem
     * Druecken ist der naechste Boost moeglich.
     */
    private void tryBoost(Player player, UUID uuid) {
        Integer remaining = boostsLeft.get(uuid);
        if (remaining == null || remaining <= 0
                || isOnGround(player) || !player.isGliding()) {
            return;
        }

        long now = System.currentTimeMillis();
        Long last = lastBoostInputMs.get(uuid);
        boolean sameHeldClick = last != null && now - last < BOOST_HELD_INPUT_WINDOW_MS;
        lastBoostInputMs.put(uuid, now);
        if (sameHeldClick) {
            return;
        }

        boost(player);

        int newRemaining = remaining - 1;
        if (newRemaining <= 0) {
            boostsLeft.remove(uuid);
        } else {
            boostsLeft.put(uuid, newRemaining);
        }

        if (SEND_MESSAGES) {
            player.sendMessage(newRemaining == 1
                    ? "Boost! Noch 1 Boost übrig."
                    : "Boost! Keine Boosts mehr übrig.");
        }
    }

    /** Horizontale Beschleunigung in Blickrichtung plus leichter Vertikalschub. */
    private void boost(Player player) {
        Vector look = player.getLocation().getDirection();
        Vector horizontal = new Vector(look.getX(), 0.0, look.getZ());
        if (horizontal.lengthSquared() > 0.0) {
            horizontal.normalize().multiply(BOOST_HORIZONTAL_STRENGTH);
        }

        Vector velocity = player.getVelocity();
        player.setVelocity(new Vector(
                velocity.getX() + horizontal.getX(),
                Math.max(velocity.getY(), 0.0) + BOOST_VERTICAL_STRENGTH,
                velocity.getZ() + horizontal.getZ()
        ));
    }

    // =====================================================================
    // Schutz: Gliding kann nicht vorzeitig beendet werden
    // =====================================================================

    @EventHandler(ignoreCancelled = true)
    public void onEntityToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        // Stop-Glide blockieren, solange aktiv und nicht gelandet.
        if (hasElytraActive.contains(uuid) && !event.isGliding() && !isOnGround(player)) {
            event.setCancelled(true);
        }
    }

    // =====================================================================
    // Schutz: Die Spawn-Elytra kann nicht in Inventar/Drops gelangen
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!hasElytraActive.contains(uuid)) {
            return;
        }

        // Sicherheitsnetz: Falls die Spawn-Elytra den Ruestungsslot verlassen hat
        // (z. B. durch ein anderes Plugin), Chestplate sofort restaurieren.
        restoreChestplateIfElytraGone(player);

        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (!isProtectedItem(current) && !isProtectedItem(cursor)) {
            return;
        }

        // Jegliche Manipulation an der Spawn-Elytra blockieren.
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!hasElytraActive.contains(uuid)) {
            return;
        }

        for (int rawSlot : event.getRawSlots()) {
            ItemStack item = event.getView().getItem(rawSlot);
            if (isProtectedItem(item)) {
                event.setCancelled(true);
                restoreChestplateIfElytraGone(player);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (!hasElytraActive.contains(uuid)) {
            return;
        }
        if (isProtectedItem(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // =====================================================================
    // Schutz: Fremde Aenderungen am Chestplate-Slot rueckgaengig machen
    // =====================================================================

    /**
     * Moderner Ersatz fuer das obsolete PlayerArmorChangeEvent: wird bei jeder
     * Aenderung an Entity-Equipment gefeuert. Wenn die Spawn-Elytra den
     * Chestplate-Slot verlaesst, setzen wir die Original-Chestplate sofort
     * zurueck, damit dem Spieler nichts abhandenkommen kann.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onEquipmentChanged(EntityEquipmentChangedEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        if (!hasElytraActive.contains(uuid)) {
            return;
        }

        EntityEquipmentChangedEvent.EquipmentChange change =
                event.getEquipmentChanges().get(EquipmentSlot.CHEST);
        if (change == null) {
            return;
        }

        boolean hadSpawnElytra = isSpawnElytraItem(change.oldItem());
        boolean hasSpawnElytra = isSpawnElytraItem(change.newItem());
        if (hadSpawnElytra && !hasSpawnElytra) {
            restoreChestplate(player);
        }
    }

    // =====================================================================
    // Schutz: Spawn-Elytra loest kein Elytra-Advancement aus
    // =====================================================================

    /**
     * Die Spawn-Elytra ist technisch Material.ELYTRA und wuerde dadurch das
     * Vanilla-Advancement "minecraft:end/elytra" ("Sky's the Limit")
     * ausloesen. Wird es waehrend aktiver Spawn-Elytra abgeschlossen, setzen
     * wir es unmittelbar zurueck. Fuer normale Elytra-Fluege bleibt der Event
     * voellig unberuehrt, und bereits vorher verdiente Advancements werden nie
     * entfernt (denn ohne Neuausloesung feuert der Event gar nicht erneut).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancementDone(PlayerAdvancementDoneEvent event) {
        if (elytraAdvancement == null
                || !elytraAdvancement.getKey().equals(event.getAdvancement().getKey())) {
            return;
        }

        Player player = event.getPlayer();
        if (!hasElytraActive.contains(player.getUniqueId())) {
            return;
        }

        // Chat-Broadcast ("... hat den Fortschritt [Sky's the Limit] gemacht")
        // unterdruecken, da das Advancement sofort wieder zurueckgesetzt wird.
        event.message(null);

        AdvancementProgress progress = player.getAdvancementProgress(elytraAdvancement);
        for (String criterion : progress.getAwardedCriteria()) {
            progress.revokeCriteria(criterion);
        }
    }

    // =====================================================================
    // Deaktivierung
    // =====================================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Die Spawn-Elytra darf nie im Todes-Drop landen.
        event.getDrops().removeIf(this::isProtectedItem);
        clearPlayerState(event.getPlayer(), false);
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        clearPlayerState(event.getPlayer(), true);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerState(event.getPlayer(), false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!hasElytraActive.contains(player.getUniqueId())) {
            return;
        }
        switch (event.getCause()) {
            case NETHER_PORTAL, END_PORTAL, END_GATEWAY -> clearPlayerState(player, true);
            default -> {
            }
        }
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (event.getNewGameMode() == GameMode.SPECTATOR
                && hasElytraActive.contains(event.getPlayer().getUniqueId())) {
            clearPlayerState(event.getPlayer(), true);
        }
    }

    /**
     * Sicherheitsnetz nach Reload/Crash: Markierte Items aus einem frueheren
     * Flug entfernen und den Zustand vollstaendig zuruecksetzen.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        PlayerInventory inv = player.getInventory();
        removeMarkedItems(inv);
        ItemStack chest = inv.getChestplate();
        if (isSpawnElytraItem(chest)) {
            inv.setChestplate(null);
        }

        hasElytraActive.remove(uuid);
        hasJumpedFromSpawn.remove(uuid);
        hasBeenAirborneFromSpawn.remove(uuid);
        boostsLeft.remove(uuid);
        lastBoostInputMs.remove(uuid);
        savedChestplates.remove(uuid);
    }

    // =====================================================================
    // Aktivieren / Deaktivieren
    // =====================================================================

    private void activateSpawnElytra(Player player) {
        UUID uuid = player.getUniqueId();

        if (hasElytraActive.contains(uuid)) {
            return;
        }

        // Urspruengliche Chestplate speichern (exakte Kopie inkl. Meta/Enchantments).
        ItemStack currentChestplate = player.getInventory().getChestplate();
        savedChestplates.put(uuid, currentChestplate == null ? null : currentChestplate.clone());

        // Temporaere, unzerstoerbare und markierte Elytra anlegen. Sie ist damit
        // unsichtbar unkenntlich geschuetzt: nicht stapelbar, nicht reparierbar,
        // per PDC eindeutig als Spawn-Elytra identifizierbar.
        player.getInventory().setChestplate(createSpawnElytra());

        hasJumpedFromSpawn.remove(uuid);
        hasBeenAirborneFromSpawn.remove(uuid);
        hasElytraActive.add(uuid);
        boostsLeft.put(uuid, MAX_BOOSTS);

        // Erst beim Fallen aktivieren.
        if (player.getVelocity().getY() <= 0.0) {
            player.setGliding(true);
        }

        if (SEND_MESSAGES) {
            player.sendMessage("Spawn-Elytra aktiviert! Linksklick = Boost. Du hast "
                    + MAX_BOOSTS + " Boosts.");
        }
    }

    private void clearPlayerState(Player player, boolean sendMessage) {
        UUID uuid = player.getUniqueId();

        boolean wasActive = hasElytraActive.remove(uuid);

        hasJumpedFromSpawn.remove(uuid);
        hasBeenAirborneFromSpawn.remove(uuid);
        boostsLeft.remove(uuid);
        lastBoostInputMs.remove(uuid);

        if (wasActive) {
            deactivate(player, sendMessage);
        } else {
            savedChestplates.remove(uuid);
        }
    }

    private void deactivate(Player player, boolean sendMessage) {
        // Wichtig: setGliding erst nach dem Entfernen aus hasElytraActive,
        // damit der Glide-Schutz nicht dazwischenfunkt.
        if (player.isGliding()) {
            player.setGliding(false);
        }

        // Temporaere Elytra entfernen (Ruestungsslot + evtl. Inventar-Fallbacks).
        ItemStack chest = player.getInventory().getChestplate();
        if (chest != null && isSpawnElytraItem(chest)) {
            player.getInventory().setChestplate(null);
        }
        removeMarkedItems(player.getInventory());

        // Urspruengliche Chestplate exakt wiederherstellen.
        restoreChestplate(player);

        if (sendMessage && SEND_MESSAGES) {
            player.sendMessage("Spawn-Elytra deaktiviert.");
        }
    }

    // =====================================================================
    // Hilfsmethoden
    // =====================================================================

    private ItemStack createSpawnElytra() {
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        ItemMeta meta = elytra.getItemMeta();
        if (meta != null) {
            meta.setUnbreakable(true);
            meta.getPersistentDataContainer().set(spawnElytraKey, PersistentDataType.BYTE, (byte) 1);
            elytra.setItemMeta(meta);
        }
        return elytra;
    }

    private boolean isSpawnElytraItem(ItemStack item) {
        if (item == null || item.getType() != Material.ELYTRA) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null
                && meta.getPersistentDataContainer().has(spawnElytraKey, PersistentDataType.BYTE);
    }

    /**
     * Serverseitiger Boden-Kontakt. Player.isOnGround() ist deprecated
     * (client-kontrolliert); die Entity-Variante liest den Server-Zustand.
     */
    private boolean isOnGround(Player player) {
        return ((Entity) player).isOnGround();
    }

    private boolean isProtectedItem(ItemStack item) {
        return isSpawnElytraItem(item);
    }

    /** Alle Items, die zu einem Brust-Slot gehoeren (wuerden die Elytra verdraengen). */
    private boolean isChestArmor(Material material) {
        return material == Material.ELYTRA
                || material == Material.LEATHER_CHESTPLATE
                || material == Material.CHAINMAIL_CHESTPLATE
                || material == Material.IRON_CHESTPLATE
                || material == Material.GOLDEN_CHESTPLATE
                || material == Material.DIAMOND_CHESTPLATE
                || material == Material.NETHERITE_CHESTPLATE;
    }

    /**
     * Falls die Spawn-Elytra den Chestplate-Slot verlassen hat, den Flug sofort
     * sicher beenden und die Original-Chestplate zurueckgeben.
     */
    private boolean restoreChestplateIfElytraGone(Player player) {
        UUID uuid = player.getUniqueId();
        if (!savedChestplates.containsKey(uuid)) {
            return false;
        }
        ItemStack chest = player.getInventory().getChestplate();
        if (isSpawnElytraItem(chest)) {
            return false;
        }

        if (hasElytraActive.remove(uuid)) {
            if (player.isGliding()) {
                player.setGliding(false);
            }
            boostsLeft.remove(uuid);
        }
        restoreChestplate(player);
        return true;
    }

    /**
     * Original-Chestplate zurueckgeben (idempotent). Liegt wider Erwarten eine
     * fremde Brustplatte im Slot, wird diese dem Spieler zurueckgegeben, damit
     * nichts verloren geht.
     */
    private void restoreChestplate(Player player) {
        UUID uuid = player.getUniqueId();
        ItemStack saved = savedChestplates.remove(uuid);
        if (saved == null) {
            return;
        }

        ItemStack chest = player.getInventory().getChestplate();
        if (chest != null && chest.getType() != Material.AIR && !isSpawnElytraItem(chest)) {
            giveOrDrop(player, chest);
        }
        player.getInventory().setChestplate(saved);
    }

    /**
     * Fuegt das Item ins Inventar ein und droppt nicht Passendes natuerlich am
     * Boden. Gibt zurueck, was NICHT ins Inventar gepasst hat (leer = alles rein).
     */
    private HashMap<Integer, ItemStack> giveOrDrop(Player player, ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return new HashMap<>();
        }
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        return leftover;
    }

    private void removeMarkedItems(PlayerInventory inv) {
        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack item = inv.getItem(slot);
            if (isProtectedItem(item)) {
                inv.setItem(slot, null);
            }
        }
    }
}
