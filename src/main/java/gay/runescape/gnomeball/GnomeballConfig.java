package gay.runescape.gnomeball;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("gnomeball")
public interface GnomeballConfig extends Config
{
    @ConfigItem(
        keyName = "showOverlay",
        name = "Show player overlays",
        description = "Show player overlays above player heads",
        position = 0
    )
    default boolean showOverlay()
    {
        return true;
    }

    @ConfigItem(
        keyName = "showTileOverlay",
        name = "Show tile overlay",
        description = "Highlight shared tiles on the ground",
        position = 1
    )
    default boolean showTileOverlay()
    {
        return true;
    }
}
