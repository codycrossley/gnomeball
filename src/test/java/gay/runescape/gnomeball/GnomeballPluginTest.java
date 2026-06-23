package gay.runescape.gnomeball;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class GnomeballPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(GnomeballPlugin.class);
        RuneLite.main(args);
    }
}