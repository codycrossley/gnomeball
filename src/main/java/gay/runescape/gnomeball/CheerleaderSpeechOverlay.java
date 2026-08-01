package gay.runescape.gnomeball;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Draws whatever each cheerleader is currently saying (see {@link CheerleaderRenderer#getActiveSpeechBubbles})
 * above its head -- plain Graphics2D text, not a native overhead-text bubble, since
 * {@code RuneLiteObject} has no such capability of its own the way a real Actor does.
 * {@link Perspective#getCanvasTextLocation} handles the world-to-screen conversion for an
 * arbitrary point, the same underlying mechanism {@code Actor.getCanvasTextLocation} itself uses
 * for real players/NPCs. */
public class CheerleaderSpeechOverlay extends Overlay
{
    private static final int TEXT_HEIGHT_OFFSET = 150; // world-unit height above the tile the text floats at -- adjust if it doesn't line up with the model visually

    private final Client client;
    private final GnomeballPlugin plugin;
    private final CheerleaderRenderer cheerleaderRenderer;

    public CheerleaderSpeechOverlay(Client client, GnomeballPlugin plugin, CheerleaderRenderer cheerleaderRenderer)
    {
        this.client = client;
        this.plugin = plugin;
        this.cheerleaderRenderer = cheerleaderRenderer;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return null;

        g.setFont(FontManager.getRunescapeBoldFont());

        for (CheerleaderRenderer.SpeechBubble bubble : cheerleaderRenderer.getActiveSpeechBubbles())
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), bubble.point);
            if (lp == null) continue;

            // Already centered around the text's own width -- that's why the text is passed in,
            // same as Actor.getCanvasTextLocation's behavior for real players/NPCs.
            Point loc = Perspective.getCanvasTextLocation(client, g, lp, bubble.text, TEXT_HEIGHT_OFFSET);
            if (loc == null) continue;

            g.setColor(Color.BLACK);
            g.drawString(bubble.text, loc.getX() + 1, loc.getY() + 1);
            g.setColor(Color.WHITE);
            g.drawString(bubble.text, loc.getX(), loc.getY());
        }

        return null;
    }
}
