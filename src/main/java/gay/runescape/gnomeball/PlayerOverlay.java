package gay.runescape.gnomeball;

import java.awt.*;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.Text;

public class PlayerOverlay extends Overlay
{
    private static final Color COLOR_REFEREE = new Color(220, 180, 40);
    private static final Color COLOR_TEAM_A  = new Color(17, 104, 253);
    private static final Color COLOR_TEAM_B  = new Color(200, 60, 60);

    private final Client client;
    private final GnomeballConfig config;
    private final GnomeballPlugin plugin;
    private final RosterReducer roster;

    public PlayerOverlay(Client client, GnomeballConfig config, GnomeballPlugin plugin, RosterReducer roster)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.roster = roster;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showOverlay()) return null;
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return null;

        g.setFont(FontManager.getRunescapeBoldFont());
        FontMetrics fm = g.getFontMetrics();

        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;

            String rsn = Text.toJagexName(p.getName());
            if (rsn == null || rsn.isBlank()) continue;

            GnomeballRole role = roster.getRole(rsn);
            if (role == null) continue;

            String number = roster.getNumber(rsn);
            if (number == null || number.isEmpty()) continue;

            String text = number;
            int textWidth = fm.stringWidth(text);
            int textHeight = fm.getHeight();

            int yOffset = p.getLogicalHeight() + textHeight + 10;
            Point loc = p.getCanvasTextLocation(g, text, yOffset);
            if (loc == null) continue;

            Color color = roleColor(role);

            g.setColor(Color.BLACK);
            g.drawString(text, loc.getX() + 1, loc.getY() + 1);
            g.setColor(color);
            g.drawString(text, loc.getX(), loc.getY());
        }

        return null;
    }

    private static Color roleColor(GnomeballRole role)
    {
        switch (role)
        {
            case REFEREE: return COLOR_REFEREE;
            case TEAM_A:  return COLOR_TEAM_A;
            case TEAM_B:  return COLOR_TEAM_B;
            default:      return Color.WHITE;
        }
    }
}