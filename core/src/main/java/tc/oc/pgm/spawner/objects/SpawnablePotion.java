package tc.oc.pgm.spawner.objects;

import static tc.oc.pgm.util.bukkit.MiscUtils.MISC_UTILS;

import org.bukkit.Location;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.spawner.Spawnable;
import tc.oc.pgm.spawner.Spawner;

public class SpawnablePotion implements Spawnable {
  private final ItemStack potionItem;
  private final String spawnerId;

  public SpawnablePotion(ItemStack potionItem, String spawnerId) {
    this.spawnerId = spawnerId;
    this.potionItem = potionItem;
  }

  @Override
  public void spawn(Location location, Match match) {
    ThrownPotion potion = MISC_UTILS.spawnPotion(location, potionItem);
    potion.setMetadata(Spawner.METADATA_KEY, new FixedMetadataValue(PGM.get(), spawnerId));
  }

  @Override
  public int getSpawnCount() {
    return 1;
  }
}
