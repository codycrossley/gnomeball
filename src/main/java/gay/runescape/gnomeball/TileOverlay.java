package gay.runescape.gnomeball;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;

import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TileOverlay extends Overlay
{
    private static final Color COLOR_UNKNOWN_TYPE = new Color(255, 255, 0); // fallback for any tile type not explicitly recognized below
    private static final Color COLOR_FIELD    = new Color(255, 255, 255);
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
     * boundary) draws on top and wins — zones take rendering priority over the field they sit on.
     * GOALPOST_A/GOALPOST_B get the exact same outline treatment as ZONE_A/ZONE_B — they're a zone
     * with a recolored 3D goalpost model standing on it (see {@link GoalpostRenderer}), not a
     * separate visual category. */
    private static final List<String> OUTLINE_TYPES = List.of("FIELD", "ZONE_A", "ZONE_B", "GOALPOST_A", "GOALPOST_B");

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
        // The winning team's field flash (see resolveFieldEndFlashColor) now fires on GAME_ENDED
        // itself, so keep drawing through ENDED for just that brief window.
        boolean fieldEndFlashing = phase == GamePhase.ENDED && System.currentTimeMillis() < plugin.getFieldEndFlashUntil();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE && !fieldEndFlashing) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        renderCommittedTiles(g);

        if (plugin.isPresetPlacementMode() || plugin.isPresetRemovalMode())
        {
            renderPresetPreview(g);
        }

        return null;
    }

    // Everything renderCommittedTiles derives from the committed tile set (fillable entries,
    // per-type grouping, connectivity) depends only on that set, which changes on a TILE_MARKED/
    // TILE_UNMARKED event -- far rarer than once a render frame. Rebuilding it from scratch every
    // frame (as this used to) meant hashing/allocating over the whole field (hundreds of tiles for
    // something like the regulation RFL preset) on every single frame for the entire game, not just
    // while something was actively changing -- the same wasted-recompute pattern TimerOverlay's
    // host-message wrap had, just continuous instead of bounded to a 5s flash. Cached here, keyed
    // on TileReducer's version counter, and only rebuilt when tiles actually change.
    private int cachedTileVersion = -1;
    private List<TileReducer.TileEntry> cachedFillEntries = List.of();
    private Map<String, Set<WorldPoint>> cachedByType = Map.of();
    private Map<String, Set<WorldPoint>> cachedConnectivity = Map.of();

    private void refreshTileCache()
    {
        int v = tileReducer.version();
        if (v == cachedTileVersion) return;

        List<TileReducer.TileEntry> entries = tileReducer.snapshot();

        List<TileReducer.TileEntry> fill = new ArrayList<>();
        Map<String, Set<WorldPoint>> byType = new HashMap<>();
        for (String type : OUTLINE_TYPES) byType.put(type, new HashSet<>());

        for (TileReducer.TileEntry entry : entries)
        {
            // Rendered as a real 3D NPC model by CheerleaderRenderer instead -- would otherwise
            // double up as both a flat colored tile here and a model standing on top of it.
            boolean isCheerleader = "CHEERLEADER_A".equals(entry.tileType) || "CHEERLEADER_B".equals(entry.tileType);
            // Same for referee flags -- rendered as a model + spotanim by FlagRenderer.
            boolean isFlag = TileReducer.FLAG.equals(entry.tileType);
            Set<WorldPoint> outlineSet = byType.get(entry.tileType);
            if (outlineSet != null) outlineSet.add(entry.point);
            else if (!isCheerleader && !isFlag) fill.add(entry);
        }

        Map<String, Set<WorldPoint>> connectivity = new HashMap<>();
        for (String type : OUTLINE_TYPES) connectivity.put(type, connectivityFor(type, byType));

        cachedFillEntries = fill;
        cachedByType = byType;
        cachedConnectivity = connectivity;
        cachedTileVersion = v;
    }

    private void renderCommittedTiles(Graphics2D g)
    {
        refreshTileCache();

        for (TileReducer.TileEntry entry : cachedFillEntries)
        {
            Color base = resolveColor(entry.color, entry.tileType);
            renderFilledTile(g, entry.point, withAlpha(base, 60), withAlpha(base, 200), SOLID_STROKE);
        }

        Color oobFlash = resolveOutOfBoundsFlashColor();
        Color fieldEndFlash = resolveFieldEndFlashColor();
        boolean timerPaused = plugin.getPhase() == GamePhase.ACTIVE && plugin.isTimerPaused();
        Color fieldColor = resolveFieldOutlineColor(timerPaused);

        for (String type : OUTLINE_TYPES)
        {
            Set<WorldPoint> tiles = cachedByType.get(type);
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
            else if ("FIELD".equals(type) && fieldEndFlash != null)
            {
                // Same priority tier as the OOB flash -- brief and celebratory, so it should win
                // over the (by now white, per resolveFieldOutlineColor) idle field color.
                edgeColor = fieldEndFlash;
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
            renderOutline(g, tiles, cachedConnectivity.get(type), edgeColor, stroke);
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
     * the normal white) during LOBBY/ENDED, when there's no running clock to reflect at all. Also
     * falls back to white the instant the clock hits 0 -- same as TimerOverlay's own border --
     * rather than staying green until the server's (potentially delayed) GAME_ENDED confirmation
     * arrives. */
    private Color resolveFieldOutlineColor(boolean timerPaused)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE) return null; // LOBBY/ENDED -- stays white
        if (plugin.isClockAtZero()) return null; // clock hit 0 -- stays white too

        if (timerPaused)
        {
            long phaseMs = System.currentTimeMillis() % PAUSE_GLOW_PULSE_PERIOD_MS;
            float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / PAUSE_GLOW_PULSE_PERIOD_MS));
            int alpha = (int) (100 + 155 * pulse);
            return withAlpha(COLOR_PAUSE_GLOW, alpha);
        }

        return withAlpha(COLOR_FIELD_RUNNING, 220);
    }

    /** Returns a brief flash of the winning team's own color (the same color that team's zone
     * tiles already render in, via plugin.getTeamAColor()/getTeamBColor()) the moment
     * the host ends the game, or null if there's no flash active right now -- including the tie case,
     * where {@link GnomeballPlugin#getFieldEndFlashTeam()} is left null and nothing ever flashes.
     * Fades out over the flash's final 500ms, the same fade curve TimerOverlay's own goal-flash
     * uses. */
    private Color resolveFieldEndFlashColor()
    {
        long until = plugin.getFieldEndFlashUntil();
        long now = System.currentTimeMillis();
        if (now >= until) return null;

        String team = plugin.getFieldEndFlashTeam();
        if (team == null) return null; // tie -- no flash

        Color base = "TEAM_A".equals(team) ? plugin.getTeamAColor() : plugin.getTeamBColor();
        float alpha = Math.min(1f, (until - now) / 500f);
        return withAlpha(base, (int) (220 * alpha));
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

    /** FIELD tiles treat neighboring ZONE_A/ZONE_B/GOALPOST_A/GOALPOST_B tiles as part of the same
     * region — zones (and goalposts, which behave identically) are conceptually part of the field,
     * so FIELD's own outline should only appear where it meets genuinely unmarked ground, not at a
     * zone boundary (which the zone's own strictly-same-type outline already draws). Every other
     * type only connects to itself. */
    private static Set<WorldPoint> connectivityFor(String type, Map<String, Set<WorldPoint>> byType)
    {
        if (!"FIELD".equals(type)) return byType.getOrDefault(type, Set.of());

        Set<WorldPoint> connected = new HashSet<>(byType.getOrDefault("FIELD", Set.of()));
        connected.addAll(byType.getOrDefault("ZONE_A", Set.of()));
        connected.addAll(byType.getOrDefault("ZONE_B", Set.of()));
        connected.addAll(byType.getOrDefault("GOALPOST_A", Set.of()));
        connected.addAll(byType.getOrDefault("GOALPOST_B", Set.of()));
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

    /** Tile points are instance coordinates straight from Tile/Player#getWorldLocation (unique per
     * tile, even inside a POH) -- deliberately NOT template coordinates, so this converts with
     * LocalPoint.fromWorld directly rather than WorldPoint.toLocalInstance, which expects a
     * template point and would find nothing for an instance one (and would light every copy of
     * a repeated POH room chunk for a template one). Outside instances the two are identical. */
    private void renderFilledTile(Graphics2D g, WorldPoint wp, Color fill, Color border, Stroke stroke)
    {
        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), wp);
        if (lp == null) return;

        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return;

        g.setColor(fill);
        g.fillPolygon(poly);
        g.setColor(border);
        g.setStroke(stroke);
        g.drawPolygon(poly);
    }

    private void drawEdgeAt(Graphics2D g, int x, int y, int plane, String direction, Color color, Stroke stroke)
    {
        // Instance coordinates, converted directly -- see renderFilledTile.
        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), new WorldPoint(x, y, plane));
        if (lp == null) return;

        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null || poly.npoints < 4) return;

        int idx = edgeIndex(direction);
        if (idx < 0) return;

        int i1 = idx, i2 = (idx + 1) % 4;
        g.setColor(color);
        g.setStroke(stroke);
        g.drawLine(poly.xpoints[i1], poly.ypoints[i1], poly.xpoints[i2], poly.ypoints[i2]);
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

    private Color defaultColorFor(String tileType)
    {
        if ("FIELD".equals(tileType)) return COLOR_FIELD;
        if ("ZONE_A".equals(tileType) || "GOALPOST_A".equals(tileType)) return plugin.getTeamAColor();
        if ("ZONE_B".equals(tileType) || "GOALPOST_B".equals(tileType)) return plugin.getTeamBColor();
        return COLOR_UNKNOWN_TYPE;
    }

    private Color resolveColor(String hex, String tileType)
    {
        if (hex == null || hex.isBlank()) return defaultColorFor(tileType);
        try { return Color.decode(hex); }
        catch (NumberFormatException e) { return defaultColorFor(tileType); }
    }
}
