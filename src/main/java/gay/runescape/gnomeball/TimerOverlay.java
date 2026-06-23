package gay.runescape.gnomeball;

import java.awt.*;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;

public class TimerOverlay extends Overlay
{
    private static final Color COLOR_PLENTY  = new Color(255, 255, 255, 220);
    private static final Color COLOR_WARNING = new Color(255, 200,  60, 220);
    private static final Color COLOR_DANGER  = new Color(255,  60,  60, 220);
    private static final Color COLOR_TEAM_A  = new Color(17, 104, 253, 220);
    private static final Color COLOR_TEAM_B  = new Color(200, 60, 60, 220);
    private static final Color BG_COLOR      = new Color(0, 0, 0, 140);
    private static final int   WARN_SECS     = 30;
    private static final int   DANGER_SECS   = 10;

    private final GnomeballPlugin plugin;

    private final Font timerFont = FontManager.getRunescapeBoldFont().deriveFont(18f);
    private final Font scoreFont = FontManager.getRunescapeBoldFont().deriveFont(14f);

    public TimerOverlay(GnomeballPlugin plugin)
    {
        this.plugin = plugin;

        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.ACTIVE && phase != GamePhase.ENDED) return null;

        final String text;
        final Color color;

        if (phase == GamePhase.ENDED)
        {
            String winner = plugin.getWinner();
            text = winner != null ? winner + " WIN!" : "GAME OVER";
            color = COLOR_PLENTY;
        }
        else
        {
            long remaining = Math.max(0, (plugin.getDeadlineMs() - System.currentTimeMillis()) / 1000);
            long minutes = remaining / 60;
            long seconds = remaining % 60;
            text = String.format("%d:%02d", minutes, seconds);

            if (remaining <= DANGER_SECS)       color = COLOR_DANGER;
            else if (remaining <= WARN_SECS)    color = COLOR_WARNING;
            else                                color = COLOR_PLENTY;
        }

        g.setFont(timerFont);
        FontMetrics timerFm = g.getFontMetrics();
        int timerW = timerFm.stringWidth(text);
        int timerH = timerFm.getAscent();
        int pad = 6;

        String scoreA = String.valueOf(plugin.getTeamAScore());
        String scoreB = String.valueOf(plugin.getTeamBScore());
        String dash = " - ";

        g.setFont(scoreFont);
        FontMetrics scoreFm = g.getFontMetrics();
        int scoreAW = scoreFm.stringWidth(scoreA);
        int dashW = scoreFm.stringWidth(dash);
        int scoreBW = scoreFm.stringWidth(scoreB);
        int scoreLineW = scoreAW + dashW + scoreBW;
        int scoreH = scoreFm.getAscent();

        int boxW = Math.max(timerW, scoreLineW) + pad * 2;
        int boxH = timerH + scoreH + pad * 3;

        g.setColor(BG_COLOR);
        g.fillRoundRect(0, 0, boxW, boxH, 6, 6);

        g.setFont(timerFont);
        int timerX = (boxW - timerW) / 2;
        g.setColor(color);
        g.drawString(text, timerX, pad + timerH - timerFm.getDescent());

        g.setFont(scoreFont);
        int scoreY = pad + timerH + pad + scoreH - scoreFm.getDescent();
        int scoreStartX = (boxW - scoreLineW) / 2;

        g.setColor(COLOR_TEAM_A);
        g.drawString(scoreA, scoreStartX, scoreY);
        g.setColor(COLOR_PLENTY);
        g.drawString(dash, scoreStartX + scoreAW, scoreY);
        g.setColor(COLOR_TEAM_B);
        g.drawString(scoreB, scoreStartX + scoreAW + dashW, scoreY);

        return new Dimension(boxW, boxH);
    }
}