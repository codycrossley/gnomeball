package gay.runescape.gnomeball;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.*;

public class TimerOverlay extends Overlay
{
    private static final Color COLOR_PLENTY   = new Color(255, 255, 255, 220);
    private static final Color COLOR_WARNING  = new Color(255, 200,  60, 220);
    private static final Color COLOR_DANGER   = new Color(255,  60,  60, 220);
    private static final Color COLOR_TEAM_A   = new Color(17, 104, 253, 220);
    private static final Color COLOR_TEAM_B   = new Color(200, 60, 60, 220);
    private static final Color COLOR_REFEREE  = new Color(60, 179, 74, 220);
    private static final Color COLOR_BALL     = new Color(255, 210, 0, 220);
    private static final Color BG_COLOR       = new Color(0, 0, 0, 140);
    private static final int   WARN_SECS     = 30;
    private static final int   DANGER_SECS   = 10;

    private final Client client;
    private final GnomeballPlugin plugin;

    private final Font timerFont = FontManager.getRunescapeBoldFont().deriveFont(18f);
    private final Font scoreFont = FontManager.getRunescapeBoldFont().deriveFont(20f);
    private final Font goalFont  = FontManager.getRunescapeBoldFont().deriveFont(48f);

    public TimerOverlay(Client client, GnomeballPlugin plugin)
    {
        this.client = client;
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
            long remaining;
            if (plugin.isTimerPaused())
                remaining = plugin.getPausedRemainingMs() / 1000;
            else
                remaining = Math.max(0, (plugin.getDeadlineMs() - System.currentTimeMillis()) / 1000);

            long minutes = remaining / 60;
            long seconds = remaining % 60;
            text = String.format("%d:%02d", minutes, seconds);

            if (plugin.isTimerPaused())         color = COLOR_REFEREE;
            else if (remaining <= DANGER_SECS)  color = COLOR_DANGER;
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

        int ballIndicatorSpace = 16;

        int boxW = Math.max(timerW, scoreLineW) + pad * 2;
        int boxH = timerH + scoreH + pad * 3 + ballIndicatorSpace;

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

        renderPossessionIndicator(g, scoreStartX, scoreAW, dashW, scoreBW, scoreY, ballIndicatorSpace);

        renderGoalFlash(g);
        renderWhistleFlash(g);
        renderInterceptionFlash(g);
        renderHostMessageFlash(g);

        return new Dimension(boxW, boxH);
    }

    private void renderPossessionIndicator(Graphics2D g, int scoreStartX, int scoreAW, int dashW, int scoreBW, int scoreY, int ballIndicatorSpace)
    {
        String holder = plugin.getBallHolder();
        if (holder == null) return;

        GnomeballRole holderRole = plugin.getRoster().getRole(holder);
        if (holderRole != GnomeballRole.TEAM_A && holderRole != GnomeballRole.TEAM_B) return;

        boolean isTeamA = holderRole == GnomeballRole.TEAM_A;
        int cx = isTeamA ? scoreStartX + scoreAW / 2 : scoreStartX + scoreAW + dashW + scoreBW / 2;
        int cy = scoreY + ballIndicatorSpace / 2 + 2;

        g.setColor(Color.BLACK);
        g.fillOval(cx - 6, cy - 6, 12, 12);
        g.setColor(COLOR_BALL);
        g.fillOval(cx - 5, cy - 5, 10, 10);
    }

    private static final Font goalScoreFont = FontManager.getRunescapeBoldFont().deriveFont(28f);
    private static final long GOAL_FLASH_DURATION = 3000;
    private static final long SCORE_TICK_DELAY = 800;

    private void renderGoalFlash(Graphics2D g)
    {
        long flashUntil = plugin.getGoalFlashUntil();
        long remaining = flashUntil - System.currentTimeMillis();
        if (remaining <= 0) return;

        String team = plugin.getGoalFlashTeam();
        if (team == null) return;

        boolean isTeamA = "TEAM_A".equals(team);
        Color teamColor = isTeamA ? COLOR_TEAM_A : COLOR_TEAM_B;
        Color otherColor = isTeamA ? COLOR_TEAM_B : COLOR_TEAM_A;

        float alpha = Math.min(1f, remaining / 500f);
        Color flashColor = withAlpha(teamColor, alpha);
        Color flashOther = withAlpha(otherColor, alpha);
        Color flashWhite = withAlpha(COLOR_PLENTY, alpha);
        Color shadowColor = new Color(0, 0, 0, (int) (180 * alpha));

        int canvasW = client.getCanvasWidth();
        int canvasH = client.getCanvasHeight();

        // "GOAL!"
        g.setFont(goalFont);
        FontMetrics goalFm = g.getFontMetrics();
        String goalText = "GOAL!";
        int goalW = goalFm.stringWidth(goalText);
        int goalX = (canvasW - goalW) / 2;
        int goalY = canvasH / 3;

        g.setColor(shadowColor);
        g.drawString(goalText, goalX + 2, goalY + 2);
        g.setColor(flashColor);
        g.drawString(goalText, goalX, goalY);

        // Score line
        long elapsed = GOAL_FLASH_DURATION - remaining;
        boolean showNewScore = elapsed >= SCORE_TICK_DELAY;

        int scoringScore = showNewScore ? plugin.getGoalFlashNewScore() : plugin.getGoalFlashOldScore();
        int otherScore = isTeamA ? plugin.getTeamBScore() : plugin.getTeamAScore();

        String aScore = String.valueOf(isTeamA ? scoringScore : otherScore);
        String bScore = String.valueOf(isTeamA ? otherScore : scoringScore);
        String dash = " - ";

        g.setFont(goalScoreFont);
        FontMetrics sFm = g.getFontMetrics();
        int aW = sFm.stringWidth(aScore);
        int dW = sFm.stringWidth(dash);
        int bW = sFm.stringWidth(bScore);
        int lineW = aW + dW + bW;
        int scoreX = (canvasW - lineW) / 2;
        int scoreY = goalY + goalFm.getHeight() + 8;

        g.setColor(shadowColor);
        g.drawString(aScore + dash + bScore, scoreX + 2, scoreY + 2);

        g.setColor(isTeamA ? flashColor : flashOther);
        g.drawString(aScore, scoreX, scoreY);
        g.setColor(flashWhite);
        g.drawString(dash, scoreX + aW, scoreY);
        g.setColor(isTeamA ? flashOther : flashColor);
        g.drawString(bScore, scoreX + aW + dW, scoreY);
    }

    private void renderWhistleFlash(Graphics2D g)
    {
        long flashUntil = plugin.getWhistleFlashUntil();
        long remaining = flashUntil - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = Math.min(1f, remaining / 500f);
        Color whistleColor = withAlpha(COLOR_REFEREE, alpha);
        Color shadowColor = new Color(0, 0, 0, (int) (180 * alpha));

        int canvasW = client.getCanvasWidth();
        int canvasH = client.getCanvasHeight();

        String whistleText = "((( whistle )))";
        g.setFont(goalFont);
        FontMetrics fm = g.getFontMetrics();
        int textW = fm.stringWidth(whistleText);
        int textX = (canvasW - textW) / 2;
        int textY = canvasH / 3;

        g.setColor(shadowColor);
        g.drawString(whistleText, textX + 2, textY + 2);
        g.setColor(whistleColor);
        g.drawString(whistleText, textX, textY);
    }

    private void renderInterceptionFlash(Graphics2D g)
    {
        long flashUntil = plugin.getInterceptionFlashUntil();
        long remaining = flashUntil - System.currentTimeMillis();
        if (remaining <= 0) return;

        String interceptingTeam = plugin.getInterceptionTeam();
        String interceptingPlayer = plugin.getInterceptionPlayer();
        if (interceptingTeam == null || interceptingPlayer == null) return;

        boolean isTeamA = "TEAM_A".equals(interceptingTeam);
        Color teamColor = isTeamA ? COLOR_TEAM_A : COLOR_TEAM_B;

        float alpha = Math.min(1f, remaining / 500f);
        Color flashColor = withAlpha(teamColor, alpha);
        Color shadowColor = new Color(0, 0, 0, (int) (180 * alpha));
        Color nameColor = withAlpha(COLOR_PLENTY, alpha);

        int canvasW = client.getCanvasWidth();
        int canvasH = client.getCanvasHeight();

        g.setFont(goalFont);
        FontMetrics goalFm = g.getFontMetrics();
        String headerText = "INTERCEPTED!";
        int headerW = goalFm.stringWidth(headerText);
        int headerX = (canvasW - headerW) / 2;
        int headerY = canvasH / 3;

        g.setColor(shadowColor);
        g.drawString(headerText, headerX + 2, headerY + 2);
        g.setColor(flashColor);
        g.drawString(headerText, headerX, headerY);

        g.setFont(goalScoreFont);
        FontMetrics nameFm = g.getFontMetrics();
        int nameW = nameFm.stringWidth(interceptingPlayer);
        int nameX = (canvasW - nameW) / 2;
        int nameY = headerY + goalFm.getHeight() + 8;

        g.setColor(shadowColor);
        g.drawString(interceptingPlayer, nameX + 2, nameY + 2);
        g.setColor(nameColor);
        g.drawString(interceptingPlayer, nameX, nameY);
    }

    private static final Font hostMessageFont = FontManager.getRunescapeBoldFont().deriveFont(20f);

    private void renderHostMessageFlash(Graphics2D g)
    {
        long flashUntil = plugin.getHostMessageFlashUntil();
        long remaining = flashUntil - System.currentTimeMillis();
        if (remaining <= 0) return;

        String message = plugin.getHostMessageText();
        if (message == null) return;

        float alpha = Math.min(1f, remaining / 500f);
        Color headerColor = withAlpha(COLOR_REFEREE, alpha);
        Color messageColor = withAlpha(COLOR_PLENTY, alpha);
        Color shadowColor = new Color(0, 0, 0, (int) (180 * alpha));

        int canvasW = client.getCanvasWidth();
        int canvasH = client.getCanvasHeight();

        g.setFont(goalScoreFont);
        FontMetrics headerFm = g.getFontMetrics();
        String headerText = "ANNOUNCEMENT";
        int headerW = headerFm.stringWidth(headerText);
        int headerX = (canvasW - headerW) / 2;
        int headerY = canvasH / 3;

        g.setColor(shadowColor);
        g.drawString(headerText, headerX + 2, headerY + 2);
        g.setColor(headerColor);
        g.drawString(headerText, headerX, headerY);

        g.setFont(hostMessageFont);
        FontMetrics msgFm = g.getFontMetrics();
        List<String> lines = wrapText(message, msgFm, (int) (canvasW * 0.8));

        int lineY = headerY + headerFm.getHeight() + 8;
        for (String line : lines)
        {
            int lineW = msgFm.stringWidth(line);
            int lineX = (canvasW - lineW) / 2;

            g.setColor(shadowColor);
            g.drawString(line, lineX + 2, lineY + 2);
            g.setColor(messageColor);
            g.drawString(line, lineX, lineY);

            lineY += msgFm.getHeight() + 4;
        }
    }

    private static List<String> wrapText(String text, FontMetrics fm, int maxWidth)
    {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+"))
        {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (current.length() > 0 && fm.stringWidth(candidate) > maxWidth)
            {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
            else
            {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private static Color withAlpha(Color c, float alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (c.getAlpha() * alpha));
    }
}