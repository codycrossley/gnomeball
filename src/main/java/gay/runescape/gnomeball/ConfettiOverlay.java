package gay.runescape.gnomeball;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/** A short, self-contained confetti burst played once a game ends (see
 * {@link GnomeballPlugin#getConfettiUntil()}). RuneLite calls {@link #render} once per client
 * render frame rather than on a fixed tick, so the particle simulation steps itself using a
 * measured wall-clock delta rather than assuming a constant frame time. */
public class ConfettiOverlay extends Overlay
{
    // Fallback burst for a tie, when there's no single winning team's color to draw from.
    private static final Color[] TIE_PALETTE = {
        new Color(255, 80, 80),
        new Color(255, 210, 0),
        new Color(60, 179, 74),
        new Color(17, 104, 253),
        new Color(255, 255, 255),
        new Color(255, 140, 0),
    };

    private static final int PARTICLE_COUNT = 130;
    private static final float FADE_TAIL_SECS = 0.6f;
    private static final float MAX_DELTA_SECS = 0.05f; // guards against a stutter causing a huge simulation jump

    private final Client client;
    private final GnomeballPlugin plugin;

    private final List<Particle> particles = new ArrayList<>();
    private long lastFrameNanos = 0;
    private long spawnedForWindow = -1; // dedupes the burst to once per celebration window

    public ConfettiOverlay(Client client, GnomeballPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        dt = Math.min(dt, MAX_DELTA_SECS);

        long until = plugin.getConfettiUntil();
        if (until != spawnedForWindow && until > System.currentTimeMillis())
        {
            spawnBurst(client.getCanvasWidth());
            spawnedForWindow = until;
        }

        if (particles.isEmpty()) return null;

        update(dt, client.getCanvasHeight());
        draw(g);
        return null;
    }

    private void spawnBurst(int canvasWidth)
    {
        Color[] palette = resolvePalette();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < PARTICLE_COUNT; i++)
        {
            Particle p = new Particle();
            p.x = r.nextFloat() * canvasWidth;
            p.y = -20 - r.nextFloat() * 200;
            p.vx = -40 + r.nextFloat() * 80;
            p.vy = 80 + r.nextFloat() * 100;
            p.rotation = r.nextFloat() * (float) (2 * Math.PI);
            p.rotSpeed = -6 + r.nextFloat() * 12;
            p.size = 3 + r.nextFloat() * 4;
            p.color = palette[r.nextInt(palette.length)];
            p.life = 2.5f + r.nextFloat() * 1.5f;
            p.rect = r.nextBoolean();
            p.swayPhase = r.nextFloat() * (float) (2 * Math.PI);
            particles.add(p);
        }
    }

    /** Shades/tints of the winning team's own color (plus white), or the original multi-color
     * burst on a tie, when {@link GnomeballPlugin#getFieldEndFlashTeam()} is null and there's no
     * single team color to draw from. */
    private Color[] resolvePalette()
    {
        String team = plugin.getFieldEndFlashTeam();
        if (team == null) return TIE_PALETTE;

        Color base = "TEAM_A".equals(team) ? plugin.getTeamAColor() : plugin.getTeamBColor();
        return new Color[] {
            shade(base, 0.55f),
            shade(base, 0.8f),
            base,
            tint(base, 0.4f),
            tint(base, 0.7f),
            Color.WHITE,
        };
    }

    private static Color shade(Color c, float factor)
    {
        return new Color((int) (c.getRed() * factor), (int) (c.getGreen() * factor), (int) (c.getBlue() * factor));
    }

    private static Color tint(Color c, float factor)
    {
        return new Color(
            (int) (c.getRed() + (255 - c.getRed()) * factor),
            (int) (c.getGreen() + (255 - c.getGreen()) * factor),
            (int) (c.getBlue() + (255 - c.getBlue()) * factor));
    }

    private void update(float dt, int canvasHeight)
    {
        Iterator<Particle> it = particles.iterator();
        while (it.hasNext())
        {
            Particle p = it.next();
            p.life -= dt;
            if (p.life <= 0 || p.y > canvasHeight + 40)
            {
                it.remove();
                continue;
            }
            p.vy += 60 * dt; // gravity
            p.x += (float) (p.vx + 25 * Math.sin(p.swayPhase + p.y * 0.02)) * dt; // sideways sway
            p.y += p.vy * dt;
            p.rotation += p.rotSpeed * dt;
        }
    }

    /** Saves/restores the transform per particle instead of calling {@code g.create()} — a
     * plain {@code AffineTransform} save/restore avoids allocating (and disposing) a whole new
     * {@code Graphics2D} 130 times a frame. */
    private void draw(Graphics2D g)
    {
        AffineTransform base = g.getTransform();
        for (Particle p : particles)
        {
            float alpha = Math.max(0f, Math.min(1f, p.life / FADE_TAIL_SECS));
            g.setColor(withAlpha(p.color, alpha));
            g.translate(p.x, p.y);
            g.rotate(p.rotation);
            if (p.rect)
                g.fillRect((int) (-p.size / 2), (int) (-p.size / 2), (int) p.size, (int) p.size);
            else
                g.fillRect((int) -p.size, -1, (int) (p.size * 2), 2);
            g.setTransform(base);
        }
    }

    private static Color withAlpha(Color c, float alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * alpha));
    }

    private static final class Particle
    {
        float x, y;
        float vx, vy;
        float rotation;
        float rotSpeed;
        float size;
        Color color;
        float life;
        boolean rect;
        float swayPhase;
    }
}
