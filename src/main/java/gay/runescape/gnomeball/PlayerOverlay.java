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
    private static final Color COLOR_REFEREE = new Color(60, 179, 74);
    private static final Color COLOR_BALL    = new Color(255, 210, 0);
    private static final Color COLOR_TEAM_A  = new Color(17, 104, 253);
    private static final Color COLOR_TEAM_B  = new Color(200, 60, 60);
    private static final Color COLOR_TAG_ARROW = new Color(255, 60, 60);
    private static final long  TAG_ARROW_PERIOD_MS = 800;

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

            String bh = plugin.getBallHolder();
            // Referees technically pass through possession (e.g. receiving it back after a goal),
            // but they never "have" the ball in the gameplay sense, so never show the icon for them.
            boolean hasBall = bh != null && bh.equalsIgnoreCase(rsn) && role != GnomeballRole.REFEREE;

            int cx = loc.getX() + textWidth / 2;
            int topY = loc.getY() - textHeight - 6;

            if (hasBall)
            {
                g.setColor(Color.BLACK);
                g.fillOval(cx - 6, topY - 6, 12, 12);
                g.setColor(COLOR_BALL);
                g.fillOval(cx - 5, topY - 5, 10, 10);
            }

            String owedTo = plugin.getTagObligationTagger();
            boolean owedTag = owedTo != null && owedTo.equalsIgnoreCase(rsn);
            boolean owedGoal = plugin.isGoalObligationActive() && role == GnomeballRole.REFEREE;
            if (owedTag || owedGoal)
            {
                drawTagArrow(g, cx, hasBall ? topY - 16 : topY);
            }

            g.setColor(Color.BLACK);
            g.drawString(text, loc.getX() + 1, loc.getY() + 1);
            g.setColor(color);
            g.drawString(text, loc.getX(), loc.getY());
        }

        return null;
    }

    /** Draws a flashing, bobbing downward-pointing arrow centered at {@code cx}, tip resting at {@code tipY}. */
    private static void drawTagArrow(Graphics2D g, int cx, int tipY)
    {
        double phase = (System.currentTimeMillis() % TAG_ARROW_PERIOD_MS) / (double) TAG_ARROW_PERIOD_MS;
        float alpha = (float) (0.4 + 0.6 * Math.abs(Math.sin(phase * Math.PI)));
        int bob = (int) Math.round(4 * Math.sin(phase * Math.PI * 2));

        int tip = tipY - 14 + bob;
        int[] xs = { cx - 6, cx + 6, cx };
        int[] ys = { tip - 8, tip - 8, tip };

        g.setColor(new Color(0, 0, 0, (int) (180 * alpha)));
        g.fillPolygon(new int[] { xs[0] + 1, xs[1] + 1, xs[2] + 1 }, new int[] { ys[0] + 1, ys[1] + 1, ys[2] + 1 }, 3);

        g.setColor(new Color(COLOR_TAG_ARROW.getRed(), COLOR_TAG_ARROW.getGreen(), COLOR_TAG_ARROW.getBlue(), (int) (255 * alpha)));
        g.fillPolygon(xs, ys, 3);
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