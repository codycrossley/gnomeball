package gay.runescape.gnomeball;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;

import java.awt.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TileOverlay extends Overlay
{
    private static final Color COLOR_UNKNOWN_TYPE = new Color(255, 255, 0); // fallback for any tile type not explicitly recognized below
    private static final Color COLOR_FIELD    = new Color(255, 255, 255);
    private static final Color COLOR_ZONE_A   = new Color(60, 120, 220);
    private static final Color COLOR_ZONE_B   = new Color(200, 60, 60);
    private static final Color COLOR_OUT_OF_BOUNDS_FLASH = new Color(255, 210, 0);
    private static final long  OUT_OF_BOUNDS_PULSE_PERIOD_MS = 260;
    // Matches TimerOverlay's own running-outline/pause-glow exactly (same colors, same pulse
    // period) -- the field outline should read as "running"/"paused" using the identical visual
    // language as the clock box's own border already does.
    private static final Color COLOR_FIELD_RUNNING = new Color(60, 179, 74);
    private static final Color COLOR_PAUSE_GLOW = new Color(255, 200, 60, 255);
    private static final long  PAUSE_GLOW_PULSE_PERIOD_MS = 1400;

    /** Types whose committed tiles render as a connected-region outline (edges only), rather than
     * each tile individually filled — these tend to cover large areas, and filling every tile
     * solid reads as an overwhelming wash of color. Order matters: drawn in this sequence, so a
     * zone edge coinciding with a field edge (e.g. a zone tile sitting right at the field's outer
     * boundary) draws on top and wins — zones take rendering priority over the field they sit on. */
    private static final List<String> OUTLINE_TYPES = List.of("FIELD", "ZONE_A", "ZONE_B");

    private static final Stroke SOLID_STROKE   = new BasicStroke(2f);
    private static final Stroke PREVIEW_STROKE = new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f);
    private static final Stroke FLASH_STROKE   = new BasicStroke(3.5f);

    private final Client client;
    private final GnomeballConfig config;
    private final GnomeballPlugin plugin;
    private final TileReducer tileReducer;

    public TileOverlay(Client client, GnomeballConfig config, GnomeballPlugin plugin, TileReducer tileReducer)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.tileReducer = tileReducer;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showTileOverlay()) return null;
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        renderCommittedTiles(g);

        if (plugin.isPresetPlacementMode() || plugin.isPresetRemovalMode())
        {
            renderPresetPreview(g);
        }

        return null;
    }

    private void renderCommittedTiles(Graphics2D g)
    {
        List<TileReducer.TileEntry> entries = tileReducer.snapshot();

        for (TileReducer.TileEntry entry : entries)
        {
            // Rendered as a real 3D NPC model by CheerleaderRenderer instead -- would otherwise
            // double up as both a flat colored tile here and a model standing on top of it.
            if ("CHEERLEADER_A".equals(entry.tileType) || "CHEERLEADER_B".equals(entry.tileType)) continue;
            if (OUTLINE_TYPES.contains(entry.tileType)) continue;
            Color base = resolveColor(entry.color, entry.tileType);
            renderFilledTile(g, entry.point, withAlpha(base, 60), withAlpha(base, 200), SOLID_STROKE);
        }

        Map<String, Set<WorldPoint>> byType = new HashMap<>();
        for (String type : OUTLINE_TYPES) byType.put(type, new HashSet<>());
        for (TileReducer.TileEntry entry : entries)
        {
            Set<WorldPoint> set = byType.get(entry.tileType);
            if (set != null) set.add(entry.point);
        }

        Color oobFlash = resolveOutOfBoundsFlashColor();
        boolean timerPaused = plugin.getPhase() == GamePhase.ACTIVE && plugin.isTimerPaused();
        Color fieldColor = resolveFieldOutlineColor(timerPaused);

        for (String type : OUTLINE_TYPES)
        {
            Set<WorldPoint> tiles = byType.get(type);
            if (tiles.isEmpty()) continue;

            Color edgeColor;
            Stroke stroke;
            if (oobFlash != null)
            {
                // A player just went out of bounds -- takes priority over the (sustained, much
                // calmer) field/pause coloring since it's the more urgent, briefer signal.
                edgeColor = oobFlash;
                stroke = FLASH_STROKE;
            }
            else if ("FIELD".equals(type) && fieldColor != null)
            {
                edgeColor = fieldColor;
                stroke = timerPaused ? FLASH_STROKE : SOLID_STROKE; // pulsing yellow gets the thicker stroke like the OOB flash does; steady green doesn't need it
            }
            else
            {
                edgeColor = withAlpha(defaultColorFor(type), 220);
                stroke = SOLID_STROKE;
            }
            renderOutline(g, tiles, connectivityFor(type, byType), edgeColor, stroke);
        }
    }

    /** Returns a pulsing red to override every field/zone outline while a player was just ruled
     * out of bounds, or null if no such flash is currently active (normal per-type colors apply).
     * Pulses continuously off {@code until - now} rather than tracking a separate start time, so
     * it needs no extra state beyond the single deadline the plugin already exposes. */
    private Color resolveOutOfBoundsFlashColor()
    {
        long until = plugin.getOutOfBoundsFlashUntil();
        long now = System.currentTimeMillis();
        if (now >= until) return null;

        long phaseMs = (until - now) % OUT_OF_BOUNDS_PULSE_PERIOD_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / OUT_OF_BOUNDS_PULSE_PERIOD_MS));
        int alpha = (int) (140 + 115 * pulse);
        return withAlpha(COLOR_OUT_OF_BOUNDS_FLASH, alpha);
    }

    /** Resolves the FIELD outline's color to match the timer's own state, using the identical
     * visual language TimerOverlay already uses on its own clock-box border: steady green while
     * the clock is actively running, the same pulsing yellow while paused, or null (falls back to
     * the normal white) during LOBBY/ENDED, when there's no running clock to reflect at all. */
    private Color resolveFieldOutlineColor(boolean timerPaused)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE) return null; // LOBBY/ENDED -- stays white

        if (timerPaused)
        {
            long phaseMs = System.currentTimeMillis() % PAUSE_GLOW_PULSE_PERIOD_MS;
            float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / PAUSE_GLOW_PULSE_PERIOD_MS));
            int alpha = (int) (100 + 155 * pulse);
            return withAlpha(COLOR_PAUSE_GLOW, alpha);
        }

        return withAlpha(COLOR_FIELD_RUNNING, 220);
    }

    private void renderPresetPreview(Graphics2D g)
    {
        FieldPreset preset = plugin.getSelectedPreset();
        if (preset == null) return;
        boolean removal = plugin.isPresetRemovalMode();

        Tile hovered = client.getTopLevelWorldView().getSelectedSceneTile();
        if (hovered == null) return;
        WorldPoint center = hovered.getWorldLocation();
        if (center == null) return;

        List<FieldPreset.PlacedTile> placed = preset.layout(center, plugin.getPresetRotationSteps());

        // Non-outline types render individually filled, same as the committed-tile split in
        // renderCommittedTiles -- and, same as there, Cheerleader tiles are skipped here too
        // (rendered as a real 3D model by CheerleaderRenderer instead), so a saved custom slot
        // that happens to include one doesn't preview as both a filled tile and a model at once.
        for (FieldPreset.PlacedTile pt : placed)
        {
            if (OUTLINE_TYPES.contains(pt.tileType)) continue;
            if ("CHEERLEADER_A".equals(pt.tileType) || "CHEERLEADER_B".equals(pt.tileType)) continue;
            Color base = removal ? new Color(255, 60, 60) : resolveColor(pt.color, pt.tileType);
            renderFilledTile(g, pt.point, withAlpha(base, 50), withAlpha(base, 220), PREVIEW_STROKE);
        }

        Map<String, Set<WorldPoint>> byType = new HashMap<>();
        for (FieldPreset.PlacedTile pt : placed)
        {
            byType.computeIfAbsent(pt.tileType, k -> new HashSet<>()).add(pt.point);
        }

        // Iterate in OUTLINE_TYPES' defined order (not the map's arbitrary entry order) so a
        // preview draws with the same zones-over-field priority as the committed rendering.
        for (String type : OUTLINE_TYPES)
        {
            Set<WorldPoint> tiles = byType.get(type);
            if (tiles == null || tiles.isEmpty()) continue;
            Color base = removal ? new Color(255, 60, 60) : defaultColorFor(type);
            renderOutline(g, tiles, connectivityFor(type, byType), withAlpha(base, 220), PREVIEW_STROKE);
        }
    }

    /** FIELD tiles treat neighboring ZONE_A/ZONE_B tiles as part of the same region — zones are
     * conceptually part of the field, so FIELD's own outline should only appear where it meets
     * genuinely unmarked ground, not at a zone boundary (which the zone's own strictly-same-type
     * outline already draws). Every other type only connects to itself. */
    private static Set<WorldPoint> connectivityFor(String type, Map<String, Set<WorldPoint>> byType)
    {
        if (!"FIELD".equals(type)) return byType.getOrDefault(type, Set.of());

        Set<WorldPoint> connected = new HashSet<>(byType.getOrDefault("FIELD", Set.of()));
        connected.addAll(byType.getOrDefault("ZONE_A", Set.of()));
        connected.addAll(byType.getOrDefault("ZONE_B", Set.of()));
        return connected;
    }

    /** Draws the outer edge of a tile region: for each tile in {@code tiles}, only the sides
     * whose neighbor isn't in {@code connected} get a line — so a solid block renders as a single
     * outline, not a grid of individually-outlined squares. {@code connected} is usually the same
     * set as {@code tiles}, except FIELD, which also connects through zone tiles (see
     * {@link #connectivityFor}) so a zone placed over/around a field doesn't leave a stray field
     * edge showing through the zone's mass. */
    private void renderOutline(Graphics2D g, Set<WorldPoint> tiles, Set<WorldPoint> connected, Color edgeColor, Stroke stroke)
    {
        for (WorldPoint wp : tiles)
        {
            int x = wp.getX(), y = wp.getY(), plane = wp.getPlane();
            if (!connected.contains(new WorldPoint(x, y + 1, plane))) drawEdgeAt(g, x, y, plane, "N", edgeColor, stroke);
            if (!connected.contains(new WorldPoint(x, y - 1, plane))) drawEdgeAt(g, x, y, plane, "S", edgeColor, stroke);
            if (!connected.contains(new WorldPoint(x + 1, y, plane))) drawEdgeAt(g, x, y, plane, "E", edgeColor, stroke);
            if (!connected.contains(new WorldPoint(x - 1, y, plane))) drawEdgeAt(g, x, y, plane, "W", edgeColor, stroke);
        }
    }

    private void renderFilledTile(Graphics2D g, WorldPoint wp, Color fill, Color border, Stroke stroke)
    {
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), wp);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            g.setColor(fill);
            g.fillPolygon(poly);
            g.setColor(border);
            g.setStroke(stroke);
            g.drawPolygon(poly);
        }
    }

    private void drawEdgeAt(Graphics2D g, int x, int y, int plane, String direction, Color color, Stroke stroke)
    {
        WorldPoint wp = new WorldPoint(x, y, plane);
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), wp);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null || poly.npoints < 4) continue;

            int idx = edgeIndex(direction);
            if (idx < 0) continue;

            int i1 = idx, i2 = (idx + 1) % 4;
            g.setColor(color);
            g.setStroke(stroke);
            g.drawLine(poly.xpoints[i1], poly.ypoints[i1], poly.xpoints[i2], poly.ypoints[i2]);
        }
    }

    private static int edgeIndex(String direction)
    {
        // Tile polygon vertices: 0=W, 1=N, 2=E, 3=S (RuneLite convention)
        switch (direction)
        {
            case "S": return 0; // W->S edge
            case "E": return 1; // S->E edge
            case "N": return 2; // E->N edge
            case "W": return 3; // S->W edge
            default: return -1;
        }
    }

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static Color defaultColorFor(String tileType)
    {
        if ("FIELD".equals(tileType)) return COLOR_FIELD;
        if ("ZONE_A".equals(tileType)) return COLOR_ZONE_A;
        if ("ZONE_B".equals(tileType)) return COLOR_ZONE_B;
        return COLOR_UNKNOWN_TYPE;
    }

    private static Color resolveColor(String hex, String tileType)
    {
        if (hex == null || hex.isBlank()) return defaultColorFor(tileType);
        try { return Color.decode(hex); }
        catch (NumberFormatException e) { return defaultColorFor(tileType); }
    }
}
