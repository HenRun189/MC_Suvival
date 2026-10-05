package me.HenRun189.mC_Suvival.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.List;

/**
 * Erzeugt und identifiziert die physische Server-Waehrung "Server Coin".
 *
 * <p>Der Coin ist ein echtes, stapelbares Item auf Basis von Material.PAPER
 * und verhaelt sich wie jeder andere Gegenstand: er kann in Kisten, Hopper,
 * Endertruhen gelegt, gedroppt und gehandelt werden. Es gibt KEINEN virtuellen
 * Kontostand - das Item selbst ist das Geld.</p>
 *
 * <p><b>Identifikation:</b> ausschliesslich ueber den
 * {@link org.bukkit.persistence.PersistentDataContainer} des Items, niemals
 * ueber Anzeigename oder Lore. Dadurch bleibt der Coin auch nach Umbenennung
 * (Amboss, zukuenftige Aenderungen an Name/Lore) eindeutig erkennbar und von
 * normalem PAPER unterscheidbar:</p>
 *
 * <pre>
 * mc_survival:item_type      = "currency"  (STRING)
 * mc_survival:currency_value = Wert, z. B. 1 (INTEGER)
 * </pre>
 *
 * <p><b>Zukunftskompatibel:</b> {@link #createCoin(int)} erlaubt spaeter
 * weitere Nominationen (1, 10, 100, 1000); {@link #isCoin(ItemStack)} und
 * {@link #getCoinValue(ItemStack)} bleiben unveraendert nutzbar. Sperren,
 * Shops oder Transaktions-Logging koennen direkt hier ansetzen.</p>
 */
public final class MoneyItem {

    /**
     * PDC-Wert fuer {@code item_type}: kennzeichnet ein Item als
     * Waehrungs-Item. Andere Item-Typen des Plugins koennen spaeter eigene
     * Werte ueber denselben Schluessel bekommen (z. B. "shop_voucher").
     */
    public static final String ITEM_TYPE_CURRENCY = "currency";

    private static final String DEFAULT_COIN_NAME = "Server Coin";

    private final NamespacedKey itemTypeKey;
    private final NamespacedKey currencyValueKey;

    public MoneyItem(Plugin plugin) {
        this.itemTypeKey = new NamespacedKey(plugin, "item_type");
        this.currencyValueKey = new NamespacedKey(plugin, "currency_value");
    }

    /** Erstellt einen Standard-Coin mit Wert 1. */
    public ItemStack createCoin() {
        return createCoin(1);
    }

    /**
     * Erstellt einen Coin mit dem gegebenen Wert.
     *
     * <p>Technisches Basis-Material ist PAPER, damit das Resource-Pack spaeter
     * genau diese Item-Variante (ueber {@code item_model} bzw.
     * {@code custom_model_data}) durch die Coin-Textur ersetzen kann, ohne
     * normale PAPER-Items anzutasten. Anzeigename und Lore sind rein kosmetisch
     * und fuer die Identifikation irrelevant.</p>
     *
     * @param value Waehrungswert des Coins (mindestens 1)
     * @return der fertige Coin als ItemStack (Amount 1, stackt bis 64)
     */
    public ItemStack createCoin(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("Coin-Wert muss mindestens 1 sein, war: " + value);
        }

        ItemStack coin = new ItemStack(Material.PAPER);
        ItemMeta meta = coin.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(DEFAULT_COIN_NAME, NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(Component.text("Value: " + value, NamedTextColor.GRAY)
                    .decoration(TextDecoration.ITALIC, false)));

            meta.getPersistentDataContainer()
                    .set(this.itemTypeKey, PersistentDataType.STRING, ITEM_TYPE_CURRENCY);
            meta.getPersistentDataContainer()
                    .set(this.currencyValueKey, PersistentDataType.INTEGER, value);

            coin.setItemMeta(meta);
        }
        return coin;
    }

    /**
     * Prueft, ob es sich um einen Server-Coin handelt. Massgeblich ist
     * ausschliesslich der PDC-Marker {@code item_type = "currency"} auf einem
     * PAPER-Item - Anzeigename, Lore und Enchantments spielen keine Rolle.
     * Normales PAPER ohne Marker liefert immer {@code false}.
     */
    public boolean isCoin(ItemStack item) {
        if (item == null || item.getType() != Material.PAPER || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        String itemType = meta.getPersistentDataContainer()
                .get(this.itemTypeKey, PersistentDataType.STRING);
        return ITEM_TYPE_CURRENCY.equals(itemType);
    }

    /**
     * Liest den Waehrungswert eines Coins.
     *
     * @return der Wert des Coins; {@code 0}, wenn kein Coin. Fehlt der
     *         Wert-Eintrag wider Erwarten (z. B. alte Items), wird 1 angenommen.
     */
    public int getCoinValue(ItemStack item) {
        if (!isCoin(item)) {
            return 0;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return 0;
        }
        Integer value = meta.getPersistentDataContainer()
                .get(this.currencyValueKey, PersistentDataType.INTEGER);
        return value == null ? 1 : value;
    }
}
