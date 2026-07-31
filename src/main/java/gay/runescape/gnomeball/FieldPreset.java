package gay.runescape.gnomeball;

import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A field layout: a flat list of tiles relative to a center anchor, with rotation support. Built-in
 * presets and host-saved custom fields (arbitrary/irregular shapes included) share this one representation. */
public final class FieldPreset
{
    public final String name;
    public final List<RelativeTile> tiles;

    public FieldPreset(String name, List<RelativeTile> tiles)
    {
        this.name = name;
        this.tiles = tiles;
    }

    @Override
    public String toString()
    {
        return name;
    }

    public boolean isEmpty()
    {
        return tiles == null || tiles.isEmpty();
    }

    /**
     * Rotates and translates this preset's tiles onto {@code center}, {@code rotationSteps}
     * quarter-turns clockwise (0-3: 0/90/180/270 degrees), via the standard clockwise transform
     * (dx,dy) -> (dy,-dx). Every current tile type (FIELD/ZONE_A/ZONE_B/CHEERLEADER_A/CHEERLEADER_B) is
     * non-directional, so only position rotates — type and color pass through unchanged. This is
     * the single source of truth for preset geometry, used identically by the live placement
     * preview and the actual commit so they can never disagree.
     */
    public List<PlacedTile> layout(WorldPoint center, int rotationSteps)
    {
        int steps = ((rotationSteps % 4) + 4) % 4;
        int plane = center.getPlane();

        List<PlacedTile> placed = new ArrayList<>(tiles.size());
        for (RelativeTile rt : tiles)
        {
            int dx = rt.dx, dy = rt.dy;
            for (int i = 0; i < steps; i++)
            {
                int ndx = dy;
                int ndy = -dx;
                dx = ndx;
                dy = ndy;
            }
            placed.add(new PlacedTile(new WorldPoint(center.getX() + dx, center.getY() + dy, plane), rt.tileType, rt.color));
        }
        return placed;
    }

    /**
     * Builds a preset from whatever tiles are currently marked, anchored at the bounding-box
     * center. Filters to the majority plane first, since {@link TileReducer} doesn't enforce
     * single-plane tile sets and a stray off-plane tile would otherwise skew the bounds.
     */
    public static FieldPreset fromTiles(String name, List<TileReducer.TileEntry> snapshot)
    {
        if (snapshot == null || snapshot.isEmpty()) return new FieldPreset(name, List.of());

        Map<Integer, Integer> countByPlane = new LinkedHashMap<>();
        for (TileReducer.TileEntry e : snapshot)
        {
            countByPlane.merge(e.point.getPlane(), 1, Integer::sum);
        }
        int majorityPlane = snapshot.get(0).point.getPlane();
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> entry : countByPlane.entrySet())
        {
            if (entry.getValue() > bestCount)
            {
                bestCount = entry.getValue();
                majorityPlane = entry.getKey();
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (TileReducer.TileEntry e : snapshot)
        {
            if (e.point.getPlane() != majorityPlane) continue;
            minX = Math.min(minX, e.point.getX());
            maxX = Math.max(maxX, e.point.getX());
            minY = Math.min(minY, e.point.getY());
            maxY = Math.max(maxY, e.point.getY());
        }
        int anchorX = Math.floorDiv(minX + maxX, 2);
        int anchorY = Math.floorDiv(minY + maxY, 2);

        List<RelativeTile> relTiles = new ArrayList<>();
        for (TileReducer.TileEntry e : snapshot)
        {
            if (e.point.getPlane() != majorityPlane) continue;
            relTiles.add(new RelativeTile(e.point.getX() - anchorX, e.point.getY() - anchorY, e.tileType, e.color));
        }
        return new FieldPreset(name, relTiles);
    }

    public static final class RelativeTile
    {
        public final int dx, dy;
        public final String tileType;
        public final String color;

        public RelativeTile(int dx, int dy, String tileType, String color)
        {
            this.dx = dx;
            this.dy = dy;
            this.tileType = tileType;
            this.color = color;
        }
    }

    public static final class PlacedTile
    {
        public final WorldPoint point;
        public final String tileType;
        public final String color;

        PlacedTile(WorldPoint point, String tileType, String color)
        {
            this.point = point;
            this.tileType = tileType;
            this.color = color;
        }
    }

    /**
     * Generates a fully-filled WxH FIELD rectangle (no endzones), computed fresh each call — this
     * is what backs the "Custom Grid" dropdown entry, letting host-chosen dimensions flow through
     * the exact same placement/rotation/removal/preview pipeline as every other preset. The
     * interior is filled, not just the perimeter, so the render-time outline (drawn only where a
     * FIELD tile's neighbor isn't also FIELD) traces a single clean boundary rather than a
     * double-lined ring, and so a tile in the middle of the field correctly counts as "on the
     * field" for {@link TileReducer#isWithinField}.
     */
    public static FieldPreset customGrid(int width, int height)
    {
        int startX = -(width / 2), startY = -(height / 2);
        int endX = startX + width - 1, endY = startY + height - 1;

        List<RelativeTile> tiles = new ArrayList<>();
        fillRect(tiles, startX, endX, startY, endY, "FIELD");
        return new FieldPreset("Custom Grid (" + width + "x" + height + ")", tiles);
    }

    public static final FieldPreset STANDARD_FIELD = buildStandardField();

    public static final List<FieldPreset> ALL = List.of(STANDARD_FIELD);

    /**
     * Generates the 25x10-with-endzones built-in field once into the flat relative-tile
     * representation, so it shares 100% of the placement/rotation/preview logic with saved
     * custom fields instead of being a separate rectangle-math code path. FIELD fills the entire
     * rectangle (including under the endzones — coexisting with ZONE_A/ZONE_B there is harmless,
     * see {@link #customGrid} for why full-fill matters for the outline render), and each endzone
     * is a plain ZONE_A/ZONE_B interior fill with no derived edge marking — the render-time
     * outline naturally draws the endzones' own boundary (including the "goal line" facing the
     * rest of the field) since FIELD continues on the other side of that line but ZONE_A/ZONE_B
     * doesn't.
     */
    private static FieldPreset buildStandardField()
    {
        int width = 25, height = 10, depth = 3;
        int startX = -(width / 2), startY = -(height / 2);
        int endX = startX + width - 1, endY = startY + height - 1;

        List<RelativeTile> tiles = new ArrayList<>();
        fillRect(tiles, startX, endX, startY, endY, "FIELD");
        fillRect(tiles, startX, startX + depth - 1, startY, endY, "ZONE_A");
        fillRect(tiles, endX - depth + 1, endX, startY, endY, "ZONE_B");

        return new FieldPreset("Standard Field (25x10)", tiles);
    }

    private static void fillRect(List<RelativeTile> tiles, int minX, int maxX, int minY, int maxY, String tileType)
    {
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                tiles.add(new RelativeTile(x, y, tileType, null));
    }
}
