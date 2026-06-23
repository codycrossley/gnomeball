package gay.runescape.gnomeball;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;

import java.awt.*;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class TileOverlay extends Overlay
{
    private static final Color COLOR_DEFAULT = new Color(255, 255, 0, 200);

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

        List<TileReducer.TileEntry> entries = tileReducer.snapshot();
        for (TileReducer.TileEntry entry : entries)
        {
            if ("STANDARD".equals(entry.tileType))
            {
                renderStandard(g, entry);
            }
            else if (entry.tileType != null && entry.tileType.startsWith("BOUNDARY_"))
            {
                renderBoundaryEdge(g, entry);
            }
        }

        if (plugin.isGridPlacementMode())
        {
            renderGridPreview(g, new Color(255, 255, 255, 160));
        }
        else if (plugin.isGridRemovalMode())
        {
            renderGridPreview(g, new Color(255, 60, 60, 160));
        }

        if (plugin.isZoneMode())
        {
            renderZonePreview(g);
        }

        return null;
    }

    private void renderStandard(Graphics2D g, TileReducer.TileEntry entry)
    {
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), entry.point);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            Color base = resolveColor(entry.color);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 60));
            g.fillPolygon(poly);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 200));
            g.setStroke(new BasicStroke(1f));
            g.drawPolygon(poly);
        }
    }

    private void renderBoundaryEdge(Graphics2D g, TileReducer.TileEntry entry)
    {
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), entry.point);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null || poly.npoints < 4) continue;

            Color base = resolveColor(entry.color);
            g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 220));
            g.setStroke(new BasicStroke(3f));

            // Tile polygon vertices: 0=W, 1=N, 2=E, 3=S (RuneLite convention)
            int edgeIndex = edgeIndex(entry.tileType);
            if (edgeIndex < 0) return;

            int i1 = edgeIndex;
            int i2 = (edgeIndex + 1) % 4;
            g.drawLine(poly.xpoints[i1], poly.ypoints[i1], poly.xpoints[i2], poly.ypoints[i2]);
        }
    }

    private void renderGridPreview(Graphics2D g, Color previewColor)
    {
        Tile hovered = client.getTopLevelWorldView().getSelectedSceneTile();
        if (hovered == null) return;
        WorldPoint center = hovered.getWorldLocation();
        if (center == null) return;

        int w = plugin.getGridWidth();
        int h = plugin.getGridHeight();
        int startX = center.getX() - w / 2;
        int startY = center.getY() - h / 2;
        int endX = startX + w - 1;
        int endY = startY + h - 1;
        int plane = center.getPlane();

        g.setColor(previewColor);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f));

        for (int x = startX; x <= endX; x++)
        {
            drawEdgeAt(g, x, startY, plane, "BOUNDARY_S");
            drawEdgeAt(g, x, endY, plane, "BOUNDARY_N");
        }
        for (int y = startY; y <= endY; y++)
        {
            drawEdgeAt(g, startX, y, plane, "BOUNDARY_W");
            drawEdgeAt(g, endX, y, plane, "BOUNDARY_E");
        }
    }

    private void renderZonePreview(Graphics2D g)
    {
        Set<WorldPoint> tiles = plugin.getZoneTiles();
        if (tiles.isEmpty()) return;

        Color base = "TEAM_A".equals(plugin.getZoneTeam())
            ? new Color(60, 120, 220)
            : new Color(200, 60, 60);
        Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), 50);
        Color edge = new Color(base.getRed(), base.getGreen(), base.getBlue(), 200);

        for (WorldPoint wp : tiles)
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
            }
        }

        g.setColor(edge);
        g.setStroke(new BasicStroke(3f));
        for (WorldPoint wp : tiles)
        {
            int x = wp.getX(), y = wp.getY(), plane = wp.getPlane();
            if (!tiles.contains(new WorldPoint(x, y + 1, plane)))
                drawEdgeAt(g, x, y, plane, "BOUNDARY_N");
            if (!tiles.contains(new WorldPoint(x, y - 1, plane)))
                drawEdgeAt(g, x, y, plane, "BOUNDARY_S");
            if (!tiles.contains(new WorldPoint(x + 1, y, plane)))
                drawEdgeAt(g, x, y, plane, "BOUNDARY_E");
            if (!tiles.contains(new WorldPoint(x - 1, y, plane)))
                drawEdgeAt(g, x, y, plane, "BOUNDARY_W");
        }
    }

    private void drawEdgeAt(Graphics2D g, int x, int y, int plane, String edgeType)
    {
        WorldPoint wp = new WorldPoint(x, y, plane);
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), wp);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null || poly.npoints < 4) continue;

            int idx = edgeIndex(edgeType);
            if (idx < 0) continue;

            int i1 = idx;
            int i2 = (idx + 1) % 4;
            g.drawLine(poly.xpoints[i1], poly.ypoints[i1], poly.xpoints[i2], poly.ypoints[i2]);
        }
    }

    private static int edgeIndex(String tileType)
    {
        switch (tileType)
        {
            case "BOUNDARY_S": return 0; // W->S edge
            case "BOUNDARY_E": return 1; // S->E edge
            case "BOUNDARY_N": return 2; // E->N edge
            case "BOUNDARY_W": return 3; // S->W edge
            default: return -1;
        }
    }

    private static Color resolveColor(String hex)
    {
        if (hex == null || hex.isBlank()) return COLOR_DEFAULT;
        try { return Color.decode(hex); }
        catch (NumberFormatException e) { return COLOR_DEFAULT; }
    }
}
