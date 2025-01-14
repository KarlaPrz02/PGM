package tc.oc.pgm.inventory;

import static tc.oc.pgm.util.inventory.InventoryUtils.INVENTORY_UTILS;
import static tc.oc.pgm.util.nms.NMSHacks.NMS_HACKS;
import static tc.oc.pgm.util.nms.PlayerUtils.PLAYER_UTILS;
import static tc.oc.pgm.util.player.PlayerComponent.player;

import com.google.common.collect.Lists;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.Permissions;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchModule;
import tc.oc.pgm.api.match.MatchScope;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.api.player.event.ObserverInteractEvent;
import tc.oc.pgm.blitz.BlitzMatchModule;
import tc.oc.pgm.doublejump.DoubleJumpMatchModule;
import tc.oc.pgm.events.ListenerScope;
import tc.oc.pgm.events.PlayerBlockTransformEvent;
import tc.oc.pgm.events.PlayerPartyChangeEvent;
import tc.oc.pgm.kits.WalkSpeedKit;
import tc.oc.pgm.spawns.events.ParticipantSpawnEvent;
import tc.oc.pgm.util.StringUtils;
import tc.oc.pgm.util.bukkit.BukkitUtils;
import tc.oc.pgm.util.bukkit.OnlinePlayerMapAdapter;
import tc.oc.pgm.util.inventory.InventoryUtils;
import tc.oc.pgm.util.named.NameStyle;
import tc.oc.pgm.util.text.TextTranslations;

@ListenerScope(MatchScope.LOADED)
public class ViewInventoryMatchModule implements MatchModule, Listener {

  public static final Duration TICK = Duration.ofMillis(50);

  private final Match match;

  private final Map<Player, InventoryTrackerEntry> monitoredInventories;
  private final Map<Player, Instant> updateQueue;

  public ViewInventoryMatchModule(Match match) {
    this.match = match;
    this.monitoredInventories = new OnlinePlayerMapAdapter<>(PGM.get());
    this.updateQueue = new OnlinePlayerMapAdapter<>(PGM.get());
    match
        .getExecutor(MatchScope.RUNNING)
        .scheduleWithFixedDelay(this::checkAllMonitoredInventories, 0, 1, TimeUnit.SECONDS);
  }

  public static int getInventoryPreviewSlot(int inventorySlot) {
    if (inventorySlot < 9) {
      return inventorySlot + 36; // put hotbar on bottom
    }
    if (inventorySlot < 36) {
      return inventorySlot; // rest of inventory
    }
    // TODO: investigate why this method doesn't work with CraftBukkit's armor slots
    return inventorySlot; // default
  }

  private void checkAllMonitoredInventories() {
    if (updateQueue.isEmpty()) return;

    updateQueue.entrySet().removeIf(entry -> {
      if (!entry.getValue().isAfter(match.getTick().instant)) {
        checkMonitoredInventories(entry.getKey());
        return true;
      }
      return false;
    });
  }

  @EventHandler
  public void closeMonitoredInventory(final InventoryCloseEvent event) {
    this.monitoredInventories.remove((Player) event.getPlayer());
  }

  @EventHandler
  public void playerQuit(final PlayerPartyChangeEvent event) {
    this.monitoredInventories.remove(event.getPlayer().getBukkit());
  }

  @EventHandler(ignoreCancelled = true)
  public void showInventories(final ObserverInteractEvent event) {
    if (event.getClickType() != ClickType.RIGHT) return;
    if (event.getPlayer().isDead()) return;

    if (event.getClickedParticipant() != null) {
      event.setCancelled(true);
      if (canPreviewInventory(event.getPlayer(), event.getClickedParticipant())) {
        this.previewPlayerInventory(
            event.getPlayer().getBukkit(), event.getClickedParticipant().getInventory());
      }
    } else if (event.getClickedEntity() instanceof InventoryHolder
        && !(event.getClickedEntity() instanceof Player)) {

      event.setCancelled(true);

      if (event.getClickedEntity() instanceof Villager) {
        INVENTORY_UTILS.openVillager(
            (Villager) event.getClickedEntity(), event.getPlayer().getBukkit());
        return;
      }

      this.previewInventory(
          event.getPlayer().getBukkit(),
          ((InventoryHolder) event.getClickedEntity()).getInventory());
    } else if (event.getClickedBlockState() instanceof InventoryHolder) {
      event.setCancelled(true);
      this.previewInventory(
          event.getPlayer().getBukkit(),
          ((InventoryHolder) event.getClickedBlockState()).getInventory());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void updateMonitoredClick(final InventoryClickEvent event) {
    if (event.getWhoClicked() instanceof Player) {
      Player player = (Player) event.getWhoClicked();

      boolean playerInventory =
          event.getInventory().getType() == InventoryType.CRAFTING; // cb bug fix
      Inventory inventory;

      if (playerInventory) {
        inventory = player.getInventory();
      } else {
        inventory = event.getInventory();
      }

      invLoop: // avoid ConcurrentModificationException by copying
      for (Map.Entry<Player, InventoryTrackerEntry> entry :
          new HashSet<>(this.monitoredInventories.entrySet())) {
        Player pl = entry.getKey();
        InventoryTrackerEntry tracker = entry.getValue();

        // because a player can only be viewing one inventory at a time,
        // this is how we determine if we have a match
        if (inventory.getViewers().isEmpty()
            || tracker.getWatched().getViewers().isEmpty()
            || inventory.getViewers().size() > tracker.getWatched().getViewers().size())
          continue invLoop;

        for (int i = 0; i < inventory.getViewers().size(); i++) {
          if (!inventory
              .getViewers()
              .get(i)
              .equals(tracker.getWatched().getViewers().get(i))) {
            continue invLoop;
          }
        }

        // a watched user is in a chest
        if (tracker.isPlayerInventory() && !playerInventory) {
          inventory = tracker.getPlayerInventory().getHolder().getInventory();
          playerInventory = true;
        }

        if (playerInventory) {
          this.previewPlayerInventory(pl, (PlayerInventory) inventory);
        } else {
          this.previewInventory(pl, inventory);
        }
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredInventory(final InventoryClickEvent event) {
    this.scheduleCheck((Player) event.getWhoClicked());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredInventory(final InventoryDragEvent event) {
    this.scheduleCheck((Player) event.getWhoClicked());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredTransform(final PlayerBlockTransformEvent event) {
    MatchPlayer player = event.getPlayer();
    if (player != null) this.scheduleCheck(player.getBukkit());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredPickup(final PlayerPickupItemEvent event) {
    this.scheduleCheck(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredDrop(final PlayerDropItemEvent event) {
    this.scheduleCheck(event.getPlayer());
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredDamage(final EntityDamageEvent event) {
    if (event.getEntity() instanceof Player) {
      this.scheduleCheck((Player) event.getEntity());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredHealth(final EntityRegainHealthEvent event) {
    if (event.getEntity() instanceof Player) {
      Player player = (Player) event.getEntity();
      if (player.getHealth() == player.getMaxHealth()) return;
      this.scheduleCheck((Player) event.getEntity());
    }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void updateMonitoredHunger(final FoodLevelChangeEvent event) {
    this.scheduleCheck((Player) event.getEntity());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void updateMonitoredSpawn(final ParticipantSpawnEvent event) {
    // must have this hack so we update player's inventories when they respawn and recieve a kit
    ViewInventoryMatchModule.this.scheduleCheck(event.getPlayer().getBukkit());
  }

  @Override
  public void unload() {
    monitoredInventories.clear();
    updateQueue.clear();
  }

  public boolean canPreviewInventory(Player viewer, Player holder) {
    MatchPlayer matchViewer = match.getPlayer(viewer);
    MatchPlayer matchHolder = match.getPlayer(holder);
    return canPreviewInventory(matchViewer, matchHolder);
  }

  public boolean canPreviewInventory(MatchPlayer viewer, MatchPlayer holder) {
    return viewer != null
        && holder != null
        && viewer.isObserving()
        && holder.isAlive()
        && viewer.getBukkit().hasPermission(Permissions.VIEW_INVENTORY);
  }

  protected void scheduleCheck(Player updater) {
    if (this.updateQueue.containsKey(updater)) return;
    this.updateQueue.put(updater, match.getTick().instant.plus(TICK));
  }

  protected void checkMonitoredInventories(Player updater) {
    for (Map.Entry<Player, InventoryTrackerEntry> entry : this.monitoredInventories.entrySet()) {
      Player pl = entry.getKey();
      InventoryTrackerEntry tracker = entry.getValue();

      InventoryHolder invHolder = tracker.getWatched().getHolder();

      if (tracker.isPlayerInventory()) {
        Player holder = (Player) invHolder;
        if (updater.equals(holder)) {
          this.previewPlayerInventory(pl, tracker.getPlayerInventory());
        }
      } else if (invHolder != null) {
        this.previewInventory(pl, invHolder.getInventory());
      }
    }
  }

  protected void previewPlayerInventory(Player viewer, PlayerInventory inventory) {
    if (viewer == null) {
      return;
    }

    Player holder = (Player) inventory.getHolder();
    // Ensure that the title of the inventory is <= 32 characters long to appease Minecraft's
    // restrictions on inventory titles
    String title = StringUtils.substring(
        TextTranslations.translateLegacy(player(holder, NameStyle.FANCY), viewer), 0, 32);

    Inventory preview = Bukkit.getServer().createInventory(viewer, 45, title);

    // handle inventory mapping
    for (int i = 0; i <= 35; i++) {
      preview.setItem(getInventoryPreviewSlot(i), inventory.getItem(i));
    }

    MatchPlayer matchHolder = this.match.getPlayer(holder);
    if (matchHolder != null && matchHolder.isParticipating()) {
      BlitzMatchModule module = matchHolder.getMatch().getModule(BlitzMatchModule.class);
      if (module != null) {
        int livesLeft = module.getNumOfLives(holder.getUniqueId());
        ItemStack lives = new ItemStack(Material.EGG, livesLeft);
        ItemMeta lifeMeta = lives.getItemMeta();
        String key = livesLeft == 1 ? "misc.life" : "misc.lives";
        lifeMeta.setDisplayName(ChatColor.GREEN
            + TextTranslations.translate(
                key, viewer, ChatColor.AQUA + String.valueOf(livesLeft) + ChatColor.GREEN));
        lives.setItemMeta(lifeMeta);
        preview.setItem(4, lives);
      }

      List<String> specialLore = new ArrayList<>();

      if (holder.getAllowFlight()) {
        specialLore.add(
            ChatColor.LIGHT_PURPLE + TextTranslations.translate("preview.flying", viewer));
      }

      DoubleJumpMatchModule djmm = matchHolder.getMatch().getModule(DoubleJumpMatchModule.class);
      if (djmm != null && djmm.hasKit(matchHolder)) {
        specialLore.add(
            ChatColor.LIGHT_PURPLE + TextTranslations.translate("preview.doubleJump", viewer));
      }

      AttributeInstance knockbackAttribute =
          matchHolder.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE);
      if (knockbackAttribute != null) {
        double knockbackResistance = knockbackAttribute.getValue();
        if (knockbackResistance > 0) {
          specialLore.add(ChatColor.LIGHT_PURPLE
              + TextTranslations.translate("preview.knockbackResistance", viewer, (int)
                  Math.ceil(knockbackResistance * 100)));
        }
      }

      double knockbackReduction = PLAYER_UTILS.getKnockbackReduction(holder);
      if (knockbackReduction > 0) {
        specialLore.add(ChatColor.LIGHT_PURPLE
            + TextTranslations.translate(
                "preview.knockbackReduction", viewer, (int) Math.ceil(knockbackReduction * 100)));
      }

      double walkSpeed = holder.getWalkSpeed();
      if (walkSpeed != WalkSpeedKit.BUKKIT_DEFAULT) {
        specialLore.add(ChatColor.LIGHT_PURPLE
            + TextTranslations.translate(
                "preview.walkSpeed",
                viewer,
                String.format("%.1f", walkSpeed / WalkSpeedKit.BUKKIT_DEFAULT)));
      }

      if (!specialLore.isEmpty()) {
        ItemStack special = new ItemStack(Material.NETHER_STAR);
        ItemMeta specialMeta = special.getItemMeta();
        specialMeta.setDisplayName(ChatColor.AQUA.toString()
            + ChatColor.ITALIC
            + TextTranslations.translate("preview.specialAbilities", viewer));
        specialMeta.setLore(specialLore);
        special.setItemMeta(specialMeta);
        preview.setItem(5, special);
      }
    }

    // potions
    boolean hasPotions = holder.getActivePotionEffects().size() > 0;
    ItemStack potions = new ItemStack(hasPotions ? Material.POTION : Material.GLASS_BOTTLE);
    ItemMeta potionMeta = potions.getItemMeta();
    potionMeta.setDisplayName(ChatColor.AQUA.toString()
        + ChatColor.ITALIC
        + TextTranslations.translate("preview.potionEffects", viewer));
    List<String> lore = Lists.newArrayList();
    if (hasPotions) {
      for (PotionEffect effect : holder.getActivePotionEffects()) {
        lore.add(ChatColor.YELLOW
            + BukkitUtils.potionEffectTypeName(effect.getType())
            + " "
            + (effect.getAmplifier() + 1));
      }
    } else {
      lore.add(ChatColor.YELLOW + TextTranslations.translate("preview.noPotionEffects", viewer));
    }
    potionMeta.setLore(lore);
    potions.setItemMeta(potionMeta);
    preview.setItem(6, potions);

    // hunger and health
    ItemStack hunger = new ItemStack(Material.COOKED_BEEF, holder.getFoodLevel());
    ItemMeta hungerMeta = hunger.getItemMeta();
    hungerMeta.setDisplayName(ChatColor.AQUA.toString()
        + ChatColor.ITALIC
        + TextTranslations.translate("preview.hungerLevel", viewer));
    hungerMeta.addItemFlags(InventoryUtils.HIDE_ADDITIONAL_FLAG);
    hunger.setItemMeta(hungerMeta);
    preview.setItem(7, hunger);

    ItemStack health = new ItemStack(Material.REDSTONE, (int) holder.getHealth());
    ItemMeta healthMeta = health.getItemMeta();
    healthMeta.setDisplayName(ChatColor.AQUA.toString()
        + ChatColor.ITALIC
        + TextTranslations.translate("preview.healthLevel", viewer));
    healthMeta.addItemFlags(InventoryUtils.HIDE_ADDITIONAL_FLAG);
    health.setItemMeta(healthMeta);
    preview.setItem(8, health);

    // set armor manually because craftbukkit is a derp
    preview.setItem(0, inventory.getHelmet());
    preview.setItem(1, inventory.getChestplate());
    preview.setItem(2, inventory.getLeggings());
    preview.setItem(3, inventory.getBoots());

    this.showInventoryPreview(viewer, inventory, preview);
  }

  public void previewInventory(Player viewer, Inventory realInventory) {
    if (viewer == null) {
      return;
    }

    if (realInventory instanceof PlayerInventory) {
      previewPlayerInventory(viewer, (PlayerInventory) realInventory);
    } else {
      Inventory fakeInventory;
      fakeInventory = NMS_HACKS.createFakeInventory(viewer, realInventory);
      fakeInventory.setContents(realInventory.getContents());

      this.showInventoryPreview(viewer, realInventory, fakeInventory);
    }
  }

  protected void showInventoryPreview(
      Player viewer, Inventory realInventory, Inventory fakeInventory) {
    if (viewer == null) {
      return;
    }

    InventoryTrackerEntry entry = this.monitoredInventories.get(viewer);
    if (entry != null
        && entry.getWatched().equals(realInventory)
        && entry.getPreview().getSize() == fakeInventory.getSize()) {
      entry.getPreview().setContents(fakeInventory.getContents());
    } else {
      entry = new InventoryTrackerEntry(realInventory, fakeInventory);
      this.monitoredInventories.put(viewer, entry);
      viewer.openInventory(fakeInventory);
    }
  }
}
