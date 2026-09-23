package gay.runescape.gnomeball;

import java.awt.*;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.Text;

public class PlayerOverlay extends Overlay
{
    private static final Color COLOR_REFEREE = new Color(60, 179, 74);
    private static final Color COLOR_BALL    = new Color(255, 210, 0);
    private static final Color COLOR_TAG_ARROW = new Color(255, 210, 0);
    private static final long  TAG_ARROW_PERIOD_MS = 800;
    private static final String PASS_LABEL = "PASS";
    private static final int   OUTLINE_ALPHA = 180;
    private static final Color COLOR_REFEREE_OUTLINE = new Color(60, 179, 74, 180);
    private static final int   FIELD_OUTLINE_WIDTH = 2;
    private static final int   FIELD_OUTLINE_FEATHER = 2;

    private final Client client;
    private final GnomeballConfig config;
    private final GnomeballPlugin plugin;
    private final RosterReducer roster;
    private final ModelOutlineRenderer modelOutlineRenderer;

    public PlayerOverlay(Client client, GnomeballConfig config, GnomeballPlugin plugin, RosterReducer roster, ModelOutlineRenderer modelOutlineRenderer)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.roster = roster;
        this.modelOutlineRenderer = modelOutlineRenderer;

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

        // Obligation arrows/labels are an instruction to whoever owes the pass -- a spectator
        // watching isn't a valid target for one, and seeing "PASS" hovering over someone else's
        // head could easily read as directed at them, so suppress both entirely for observers.
        Player localPlayer = client.getLocalPlayer();
        String localRsn = (localPlayer != null && localPlayer.getName() != null) ? Text.toJagexName(localPlayer.getName()) : null;
        GnomeballRole localRole = localRsn != null ? roster.getRole(localRsn) : null;
        boolean showObligationHints = localRole != GnomeballRole.OBSERVER;

        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;

            String rsn = Text.toJagexName(p.getName());
            if (rsn == null || rsn.isBlank()) continue;

            GnomeballRole role = roster.getRole(rsn);
            if (role == null) continue;

            // Team players and referees get outlined in their own role color while standing on
            // the field. Observers are left unaltered.
            if (role != GnomeballRole.OBSERVER && plugin.getTileReducer().isWithinField(p.getWorldLocation()))
            {
                Color outlineColor;
                switch (role)
                {
                    case TEAM_A:  outlineColor = withAlpha(plugin.getTeamAColor(), OUTLINE_ALPHA); break;
                    case TEAM_B:  outlineColor = withAlpha(plugin.getTeamBColor(), OUTLINE_ALPHA); break;
                    case REFEREE: outlineColor = COLOR_REFEREE_OUTLINE; break;
                    default:      outlineColor = null;
                }
                if (outlineColor != null)
                {
                    modelOutlineRenderer.drawOutline(p, FIELD_OUTLINE_WIDTH, outlineColor, FIELD_OUTLINE_FEATHER);
                }
            }

            boolean isReferee = role == GnomeballRole.REFEREE;
            String number = roster.getNumber(rsn);
            if (!isReferee && (number == null || number.isEmpty())) continue;

            // Referees get an icon instead of a jersey number, but we still measure against a
            // placeholder string so the anchor position (and vertical spacing above the head)
            // matches the numbered players.
            String text = isReferee ? "REF" : number;
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
            boolean owedDelivery = false;
            if (plugin.isObligationActive())
            {
                String obligationTeam = plugin.getObligationTeam();
                GnomeballRole opposingRole = "TEAM_A".equals(obligationTeam) ? GnomeballRole.TEAM_B : GnomeballRole.TEAM_A;

                if ("OUT_OF_BOUNDS".equals(plugin.getObligationKind()))
                {
                    // Strictly a team-to-team turnover — a referee is never a valid target.
                    owedDelivery = role == opposingRole;
                }
                else if (role == GnomeballRole.REFEREE)
                {
                    owedDelivery = true;
                }
                else if (roster.countRole(GnomeballRole.REFEREE) == 0)
                {
                    // No referee currently in the game — fall back to highlighting the
                    // opposing team as the valid delivery target.
                    owedDelivery = role == opposingRole;
                }
            }
            if (showObligationHints && (owedTag || owedDelivery))
            {
                int arrowTipY = hasBall ? topY - 16 : topY;
                drawTagArrow(g, cx, arrowTipY);
                drawPassLabel(g, cx, arrowTipY, fm);
            }

            if (isReferee)
            {
                drawCheckeredFlag(g, cx, loc.getY() - fm.getAscent() / 2);
            }
            else
            {
                g.setColor(Color.BLACK);
                g.drawString(text, loc.getX() + 1, loc.getY() + 1);
                g.setColor(color);
                g.drawString(text, loc.getX(), loc.getY());
            }
        }

        return null;
    }

    /** Draws a small checkered referee's flag (pole + checkered banner) centered at {@code (cx, cy)}. */
    private static void drawCheckeredFlag(Graphics2D g, int cx, int cy)
    {
        int poleX = cx - 10;
        int poleTop = cy - 10;
        int poleBottom = cy + 10;
        int flagW = 16, flagH = 10;
        int cols = 4, rows = 2;
        int cellW = flagW / cols;
        int cellH = flagH / rows;

        Stroke oldStroke = g.getStroke();

        // pole
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(Color.BLACK);
        g.drawLine(poleX, poleTop, poleX, poleBottom);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(216, 210, 194));
        g.drawLine(poleX, poleTop, poleX, poleBottom);
        g.setStroke(oldStroke);

        // banner outline
        g.setColor(Color.BLACK);
        g.fillRect(poleX - 1, poleTop - 1, flagW + 2, flagH + 2);

        // checker squares
        g.setColor(Color.WHITE);
        g.fillRect(poleX, poleTop, flagW, flagH);
        g.setColor(Color.BLACK);
        for (int r = 0; r < rows; r++)
        {
            for (int c = 0; c < cols; c++)
            {
                if ((r + c) % 2 == 0)
                {
                    g.fillRect(poleX + c * cellW, poleTop + r * cellH, cellW, cellH);
                }
            }
        }

        // hoist stripe at the pole edge, tying the icon back to the referee role color
        g.setColor(COLOR_REFEREE);
        g.fillRect(poleX - 1, poleTop - 1, 3, flagH + 2);
    }

    /** Draws a flashing, bobbing downward-pointing arrow centered at {@code cx}, tip resting at {@code tipY}. */
    private static void drawTagArrow(Graphics2D g, int cx, int tipY)
    {
        double phase = (System.currentTimeMillis() % TAG_ARROW_PERIOD_MS) / (double) TAG_ARROW_PERIOD_MS;
        float alpha = (float) (0.4 + 0.6 * Math.abs(Math.sin(phase * Math.PI)));
        int bob = (int) Math.round(6 * Math.sin(phase * Math.PI * 2));

        int tip = tipY - 21 + bob;
        int[] xs = { cx - 9, cx + 9, cx };
        int[] ys = { tip - 12, tip - 12, tip };

        g.setColor(new Color(0, 0, 0, (int) (180 * alpha)));
        g.fillPolygon(new int[] { xs[0] + 2, xs[1] + 2, xs[2] + 2 }, new int[] { ys[0] + 2, ys[1] + 2, ys[2] + 2 }, 3);

        g.setColor(new Color(COLOR_TAG_ARROW.getRed(), COLOR_TAG_ARROW.getGreen(), COLOR_TAG_ARROW.getBlue(), (int) (255 * alpha)));
        g.fillPolygon(xs, ys, 3);
    }

    /** Draws "PASS" centered above {@code cx}, clear of drawTagArrow's full bob range so the two
     * never overlap regardless of animation phase. */
    private static void drawPassLabel(Graphics2D g, int cx, int tipY, FontMetrics fm)
    {
        int labelWidth = fm.stringWidth(PASS_LABEL);
        int labelY = tipY - 43;

        g.setColor(Color.BLACK);
        g.drawString(PASS_LABEL, cx - labelWidth / 2 + 1, labelY + 1);
        g.setColor(COLOR_TAG_ARROW);
        g.drawString(PASS_LABEL, cx - labelWidth / 2, labelY);
    }

    private Color roleColor(GnomeballRole role)
    {
        switch (role)
        {
            case REFEREE: return COLOR_REFEREE;
            case TEAM_A:  return plugin.getTeamAColor();
            case TEAM_B:  return plugin.getTeamBColor();
            default:      return Color.WHITE;
        }
    }

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}