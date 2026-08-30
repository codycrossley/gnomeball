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
     * (dx,dy) -> (dy,-dx). Most tile types (FIELD/ZONE_A/ZONE_B/CHEERLEADER_A/CHEERLEADER_B) are
     * non-directional, so only position rotates for them — type and color pass through unchanged.
     * GOALPOST_A/GOALPOST_B carry a base facing (see {@link GoalpostRenderer}), which rotates
     * alongside position by the same 90-degrees-per-step amount (512 Jagex Angle Units), so the
     * spawned 3D model keeps facing the same way relative to the field after a rotation. This is
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
            Integer orientation = rt.orientation == null ? null : ((rt.orientation + steps * 512) % 2048 + 2048) % 2048;
            placed.add(new PlacedTile(new WorldPoint(center.getX() + dx, center.getY() + dy, plane), rt.tileType, rt.color, orientation));
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
            relTiles.add(new RelativeTile(e.point.getX() - anchorX, e.point.getY() - anchorY, e.tileType, e.color, e.orientation));
        }
        return new FieldPreset(name, relTiles);
    }

    public static final class RelativeTile
    {
        public final int dx, dy;
        public final String tileType;
        public final String color;
        public final Integer orientation; // nullable -- base facing, GOALPOST_A/B only

        public RelativeTile(int dx, int dy, String tileType, String color)
        {
            this(dx, dy, tileType, color, null);
        }

        public RelativeTile(int dx, int dy, String tileType, String color, Integer orientation)
        {
            this.dx = dx;
            this.dy = dy;
            this.tileType = tileType;
            this.color = color;
            this.orientation = orientation;
        }
    }

    public static final class PlacedTile
    {
        public final WorldPoint point;
        public final String tileType;
        public final String color;
        public final Integer orientation; // nullable -- rotated facing, GOALPOST_A/B only

        PlacedTile(WorldPoint point, String tileType, String color, Integer orientation)
        {
            this.point = point;
            this.tileType = tileType;
            this.color = color;
            this.orientation = orientation;
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
    public static final FieldPreset REGULATION_RFL_FIELD = buildRegulationRflField();
    public static final FieldPreset GNOMEBALL_FIELD = buildGnomeballField();

    public static final List<FieldPreset> ALL = List.of(STANDARD_FIELD, REGULATION_RFL_FIELD, GNOMEBALL_FIELD);

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

    /**
     * A larger 40x14-with-endzones built-in field, generated the same way as
     * {@link #buildStandardField} but at regulation-scale dimensions with a shallower 1-tile
     * endzone depth.
     */
    private static FieldPreset buildRegulationRflField()
    {
        int width = 40, height = 14, depth = 1;
        int startX = -(width / 2), startY = -(height / 2);
        int endX = startX + width - 1, endY = startY + height - 1;

        List<RelativeTile> tiles = new ArrayList<>();
        fillRect(tiles, startX, endX, startY, endY, "FIELD");
        fillRect(tiles, startX, startX + depth - 1, startY, endY, "ZONE_A");
        fillRect(tiles, endX - depth + 1, endX, startY, endY, "ZONE_B");

        return new FieldPreset("Regulation RFL (40x14)", tiles);
    }

    /**
     * A rounded, stadium-shaped 23x14 field with cheerleader spots flanking a gap at the north end
     * and single-tile ZONE_A/ZONE_B goal markers at the midline, captured from a host's live custom
     * layout (join code 7118-6431) rather than generated by rectangle math like {@link #buildStandardField}.
     * Each goal marker also carries a co-located GOALPOST_A/GOALPOST_B tile, so a recolored 3D
     * goalpost model (see {@link GoalpostRenderer}) stands right on the goal line, the same way
     * FIELD and ZONE_A/ZONE_B already coexist at that spot.
     */
    private static FieldPreset buildGnomeballField()
    {
        List<RelativeTile> tiles = new ArrayList<>();
        tiles.add(new RelativeTile(-1, 7, "CHEERLEADER_A", null));
        tiles.add(new RelativeTile(1, 7, "CHEERLEADER_B", null));
        tiles.add(new RelativeTile(-8, 6, "FIELD", null));
        tiles.add(new RelativeTile(-7, 6, "FIELD", null));
        tiles.add(new RelativeTile(-6, 6, "FIELD", null));
        tiles.add(new RelativeTile(-5, 6, "FIELD", null));
        tiles.add(new RelativeTile(-4, 6, "FIELD", null));
        tiles.add(new RelativeTile(-3, 6, "FIELD", null));
        tiles.add(new RelativeTile(-2, 6, "CHEERLEADER_A", null));
        tiles.add(new RelativeTile(2, 6, "CHEERLEADER_B", null));
        tiles.add(new RelativeTile(3, 6, "FIELD", null));
        tiles.add(new RelativeTile(4, 6, "FIELD", null));
        tiles.add(new RelativeTile(5, 6, "FIELD", null));
        tiles.add(new RelativeTile(6, 6, "FIELD", null));
        tiles.add(new RelativeTile(7, 6, "FIELD", null));
        tiles.add(new RelativeTile(8, 6, "FIELD", null));
        tiles.add(new RelativeTile(-9, 5, "FIELD", null));
        tiles.add(new RelativeTile(-8, 5, "FIELD", null));
        tiles.add(new RelativeTile(-7, 5, "FIELD", null));
        tiles.add(new RelativeTile(-6, 5, "FIELD", null));
        tiles.add(new RelativeTile(-5, 5, "FIELD", null));
        tiles.add(new RelativeTile(-4, 5, "FIELD", null));
        tiles.add(new RelativeTile(-3, 5, "FIELD", null));
        tiles.add(new RelativeTile(-2, 5, "FIELD", null));
        tiles.add(new RelativeTile(2, 5, "FIELD", null));
        tiles.add(new RelativeTile(3, 5, "FIELD", null));
        tiles.add(new RelativeTile(4, 5, "FIELD", null));
        tiles.add(new RelativeTile(5, 5, "FIELD", null));
        tiles.add(new RelativeTile(6, 5, "FIELD", null));
        tiles.add(new RelativeTile(7, 5, "FIELD", null));
        tiles.add(new RelativeTile(8, 5, "FIELD", null));
        tiles.add(new RelativeTile(9, 5, "FIELD", null));
        tiles.add(new RelativeTile(-10, 4, "FIELD", null));
        tiles.add(new RelativeTile(-9, 4, "FIELD", null));
        tiles.add(new RelativeTile(-8, 4, "FIELD", null));
        tiles.add(new RelativeTile(-7, 4, "FIELD", null));
        tiles.add(new RelativeTile(-6, 4, "FIELD", null));
        tiles.add(new RelativeTile(-5, 4, "FIELD", null));
        tiles.add(new RelativeTile(-4, 4, "FIELD", null));
        tiles.add(new RelativeTile(-3, 4, "FIELD", null));
        tiles.add(new RelativeTile(-2, 4, "FIELD", null));
        tiles.add(new RelativeTile(-1, 4, "FIELD", null));
        tiles.add(new RelativeTile(0, 4, "FIELD", null));
        tiles.add(new RelativeTile(1, 4, "FIELD", null));
        tiles.add(new RelativeTile(2, 4, "FIELD", null));
        tiles.add(new RelativeTile(3, 4, "FIELD", null));
        tiles.add(new RelativeTile(4, 4, "FIELD", null));
        tiles.add(new RelativeTile(5, 4, "FIELD", null));
        tiles.add(new RelativeTile(6, 4, "FIELD", null));
        tiles.add(new RelativeTile(7, 4, "FIELD", null));
        tiles.add(new RelativeTile(8, 4, "FIELD", null));
        tiles.add(new RelativeTile(9, 4, "FIELD", null));
        tiles.add(new RelativeTile(10, 4, "FIELD", null));
        tiles.add(new RelativeTile(-11, 3, "FIELD", null));
        tiles.add(new RelativeTile(-10, 3, "FIELD", null));
        tiles.add(new RelativeTile(-9, 3, "FIELD", null));
        tiles.add(new RelativeTile(-8, 3, "FIELD", null));
        tiles.add(new RelativeTile(-7, 3, "FIELD", null));
        tiles.add(new RelativeTile(-6, 3, "FIELD", null));
        tiles.add(new RelativeTile(-5, 3, "FIELD", null));
        tiles.add(new RelativeTile(-4, 3, "FIELD", null));
        tiles.add(new RelativeTile(-3, 3, "FIELD", null));
        tiles.add(new RelativeTile(-2, 3, "FIELD", null));
        tiles.add(new RelativeTile(-1, 3, "FIELD", null));
        tiles.add(new RelativeTile(0, 3, "FIELD", null));
        tiles.add(new RelativeTile(1, 3, "FIELD", null));
        tiles.add(new RelativeTile(2, 3, "FIELD", null));
        tiles.add(new RelativeTile(3, 3, "FIELD", null));
        tiles.add(new RelativeTile(4, 3, "FIELD", null));
        tiles.add(new RelativeTile(5, 3, "FIELD", null));
        tiles.add(new RelativeTile(6, 3, "FIELD", null));
        tiles.add(new RelativeTile(7, 3, "FIELD", null));
        tiles.add(new RelativeTile(8, 3, "FIELD", null));
        tiles.add(new RelativeTile(9, 3, "FIELD", null));
        tiles.add(new RelativeTile(10, 3, "FIELD", null));
        tiles.add(new RelativeTile(11, 3, "FIELD", null));
        tiles.add(new RelativeTile(-11, 2, "FIELD", null));
        tiles.add(new RelativeTile(-10, 2, "FIELD", null));
        tiles.add(new RelativeTile(-9, 2, "FIELD", null));
        tiles.add(new RelativeTile(-8, 2, "FIELD", null));
        tiles.add(new RelativeTile(-7, 2, "FIELD", null));
        tiles.add(new RelativeTile(-6, 2, "FIELD", null));
        tiles.add(new RelativeTile(-5, 2, "FIELD", null));
        tiles.add(new RelativeTile(-4, 2, "FIELD", null));
        tiles.add(new RelativeTile(-3, 2, "FIELD", null));
        tiles.add(new RelativeTile(-2, 2, "FIELD", null));
        tiles.add(new RelativeTile(-1, 2, "FIELD", null));
        tiles.add(new RelativeTile(0, 2, "FIELD", null));
        tiles.add(new RelativeTile(1, 2, "FIELD", null));
        tiles.add(new RelativeTile(2, 2, "FIELD", null));
        tiles.add(new RelativeTile(3, 2, "FIELD", null));
        tiles.add(new RelativeTile(4, 2, "FIELD", null));
        tiles.add(new RelativeTile(5, 2, "FIELD", null));
        tiles.add(new RelativeTile(6, 2, "FIELD", null));
        tiles.add(new RelativeTile(7, 2, "FIELD", null));
        tiles.add(new RelativeTile(8, 2, "FIELD", null));
        tiles.add(new RelativeTile(9, 2, "FIELD", null));
        tiles.add(new RelativeTile(10, 2, "FIELD", null));
        tiles.add(new RelativeTile(11, 2, "FIELD", null));
        tiles.add(new RelativeTile(-11, 1, "FIELD", null));
        tiles.add(new RelativeTile(-10, 1, "FIELD", null));
        tiles.add(new RelativeTile(-9, 1, "FIELD", null));
        tiles.add(new RelativeTile(-8, 1, "FIELD", null));
        tiles.add(new RelativeTile(-7, 1, "FIELD", null));
        tiles.add(new RelativeTile(-6, 1, "FIELD", null));
        tiles.add(new RelativeTile(-5, 1, "FIELD", null));
        tiles.add(new RelativeTile(-4, 1, "FIELD", null));
        tiles.add(new RelativeTile(-3, 1, "FIELD", null));
        tiles.add(new RelativeTile(-2, 1, "FIELD", null));
        tiles.add(new RelativeTile(-1, 1, "FIELD", null));
        tiles.add(new RelativeTile(0, 1, "FIELD", null));
        tiles.add(new RelativeTile(1, 1, "FIELD", null));
        tiles.add(new RelativeTile(2, 1, "FIELD", null));
        tiles.add(new RelativeTile(3, 1, "FIELD", null));
        tiles.add(new RelativeTile(4, 1, "FIELD", null));
        tiles.add(new RelativeTile(5, 1, "FIELD", null));
        tiles.add(new RelativeTile(6, 1, "FIELD", null));
        tiles.add(new RelativeTile(7, 1, "FIELD", null));
        tiles.add(new RelativeTile(8, 1, "FIELD", null));
        tiles.add(new RelativeTile(9, 1, "FIELD", null));
        tiles.add(new RelativeTile(10, 1, "FIELD", null));
        tiles.add(new RelativeTile(11, 1, "FIELD", null));
        tiles.add(new RelativeTile(-11, 0, "FIELD", null));
        tiles.add(new RelativeTile(-10, 0, "FIELD", null));
        tiles.add(new RelativeTile(-10, 0, "ZONE_A", null));
        tiles.add(new RelativeTile(-10, 0, "GOALPOST_A", null, 1536));
        tiles.add(new RelativeTile(-9, 0, "FIELD", null));
        tiles.add(new RelativeTile(-8, 0, "FIELD", null));
        tiles.add(new RelativeTile(-7, 0, "FIELD", null));
        tiles.add(new RelativeTile(-6, 0, "FIELD", null));
        tiles.add(new RelativeTile(-5, 0, "FIELD", null));
        tiles.add(new RelativeTile(-4, 0, "FIELD", null));
        tiles.add(new RelativeTile(-3, 0, "FIELD", null));
        tiles.add(new RelativeTile(-2, 0, "FIELD", null));
        tiles.add(new RelativeTile(-1, 0, "FIELD", null));
        tiles.add(new RelativeTile(0, 0, "FIELD", null));
        tiles.add(new RelativeTile(1, 0, "FIELD", null));
        tiles.add(new RelativeTile(2, 0, "FIELD", null));
        tiles.add(new RelativeTile(3, 0, "FIELD", null));
        tiles.add(new RelativeTile(4, 0, "FIELD", null));
        tiles.add(new RelativeTile(5, 0, "FIELD", null));
        tiles.add(new RelativeTile(6, 0, "FIELD", null));
        tiles.add(new RelativeTile(7, 0, "FIELD", null));
        tiles.add(new RelativeTile(8, 0, "FIELD", null));
        tiles.add(new RelativeTile(9, 0, "FIELD", null));
        tiles.add(new RelativeTile(10, 0, "FIELD", null));
        tiles.add(new RelativeTile(10, 0, "ZONE_B", null));
        tiles.add(new RelativeTile(10, 0, "GOALPOST_B", null, 512));
        tiles.add(new RelativeTile(11, 0, "FIELD", null));
        tiles.add(new RelativeTile(-11, -1, "FIELD", null));
        tiles.add(new RelativeTile(-10, -1, "FIELD", null));
        tiles.add(new RelativeTile(-9, -1, "FIELD", null));
        tiles.add(new RelativeTile(-8, -1, "FIELD", null));
        tiles.add(new RelativeTile(-7, -1, "FIELD", null));
        tiles.add(new RelativeTile(-6, -1, "FIELD", null));
        tiles.add(new RelativeTile(-5, -1, "FIELD", null));
        tiles.add(new RelativeTile(-4, -1, "FIELD", null));
        tiles.add(new RelativeTile(-3, -1, "FIELD", null));
        tiles.add(new RelativeTile(-2, -1, "FIELD", null));
        tiles.add(new RelativeTile(-1, -1, "FIELD", null));
        tiles.add(new RelativeTile(0, -1, "FIELD", null));
        tiles.add(new RelativeTile(1, -1, "FIELD", null));
        tiles.add(new RelativeTile(2, -1, "FIELD", null));
        tiles.add(new RelativeTile(3, -1, "FIELD", null));
        tiles.add(new RelativeTile(4, -1, "FIELD", null));
        tiles.add(new RelativeTile(5, -1, "FIELD", null));
        tiles.add(new RelativeTile(6, -1, "FIELD", null));
        tiles.add(new RelativeTile(7, -1, "FIELD", null));
        tiles.add(new RelativeTile(8, -1, "FIELD", null));
        tiles.add(new RelativeTile(9, -1, "FIELD", null));
        tiles.add(new RelativeTile(10, -1, "FIELD", null));
        tiles.add(new RelativeTile(11, -1, "FIELD", null));
        tiles.add(new RelativeTile(-11, -2, "FIELD", null));
        tiles.add(new RelativeTile(-10, -2, "FIELD", null));
        tiles.add(new RelativeTile(-9, -2, "FIELD", null));
        tiles.add(new RelativeTile(-8, -2, "FIELD", null));
        tiles.add(new RelativeTile(-7, -2, "FIELD", null));
        tiles.add(new RelativeTile(-6, -2, "FIELD", null));
        tiles.add(new RelativeTile(-5, -2, "FIELD", null));
        tiles.add(new RelativeTile(-4, -2, "FIELD", null));
        tiles.add(new RelativeTile(-3, -2, "FIELD", null));
        tiles.add(new RelativeTile(-2, -2, "FIELD", null));
        tiles.add(new RelativeTile(-1, -2, "FIELD", null));
        tiles.add(new RelativeTile(0, -2, "FIELD", null));
        tiles.add(new RelativeTile(1, -2, "FIELD", null));
        tiles.add(new RelativeTile(2, -2, "FIELD", null));
        tiles.add(new RelativeTile(3, -2, "FIELD", null));
        tiles.add(new RelativeTile(4, -2, "FIELD", null));
        tiles.add(new RelativeTile(5, -2, "FIELD", null));
        tiles.add(new RelativeTile(6, -2, "FIELD", null));
        tiles.add(new RelativeTile(7, -2, "FIELD", null));
        tiles.add(new RelativeTile(8, -2, "FIELD", null));
        tiles.add(new RelativeTile(9, -2, "FIELD", null));
        tiles.add(new RelativeTile(10, -2, "FIELD", null));
        tiles.add(new RelativeTile(11, -2, "FIELD", null));
        tiles.add(new RelativeTile(-11, -3, "FIELD", null));
        tiles.add(new RelativeTile(-10, -3, "FIELD", null));
        tiles.add(new RelativeTile(-9, -3, "FIELD", null));
        tiles.add(new RelativeTile(-8, -3, "FIELD", null));
        tiles.add(new RelativeTile(-7, -3, "FIELD", null));
        tiles.add(new RelativeTile(-6, -3, "FIELD", null));
        tiles.add(new RelativeTile(-5, -3, "FIELD", null));
        tiles.add(new RelativeTile(-4, -3, "FIELD", null));
        tiles.add(new RelativeTile(-3, -3, "FIELD", null));
        tiles.add(new RelativeTile(-2, -3, "FIELD", null));
        tiles.add(new RelativeTile(-1, -3, "FIELD", null));
        tiles.add(new RelativeTile(0, -3, "FIELD", null));
        tiles.add(new RelativeTile(1, -3, "FIELD", null));
        tiles.add(new RelativeTile(2, -3, "FIELD", null));
        tiles.add(new RelativeTile(3, -3, "FIELD", null));
        tiles.add(new RelativeTile(4, -3, "FIELD", null));
        tiles.add(new RelativeTile(5, -3, "FIELD", null));
        tiles.add(new RelativeTile(6, -3, "FIELD", null));
        tiles.add(new RelativeTile(7, -3, "FIELD", null));
        tiles.add(new RelativeTile(8, -3, "FIELD", null));
        tiles.add(new RelativeTile(9, -3, "FIELD", null));
        tiles.add(new RelativeTile(10, -3, "FIELD", null));
        tiles.add(new RelativeTile(11, -3, "FIELD", null));
        tiles.add(new RelativeTile(-10, -4, "FIELD", null));
        tiles.add(new RelativeTile(-9, -4, "FIELD", null));
        tiles.add(new RelativeTile(-8, -4, "FIELD", null));
        tiles.add(new RelativeTile(-7, -4, "FIELD", null));
        tiles.add(new RelativeTile(-6, -4, "FIELD", null));
        tiles.add(new RelativeTile(-5, -4, "FIELD", null));
        tiles.add(new RelativeTile(-4, -4, "FIELD", null));
        tiles.add(new RelativeTile(-3, -4, "FIELD", null));
        tiles.add(new RelativeTile(-2, -4, "FIELD", null));
        tiles.add(new RelativeTile(-1, -4, "FIELD", null));
        tiles.add(new RelativeTile(0, -4, "FIELD", null));
        tiles.add(new RelativeTile(1, -4, "FIELD", null));
        tiles.add(new RelativeTile(2, -4, "FIELD", null));
        tiles.add(new RelativeTile(3, -4, "FIELD", null));
        tiles.add(new RelativeTile(4, -4, "FIELD", null));
        tiles.add(new RelativeTile(5, -4, "FIELD", null));
        tiles.add(new RelativeTile(6, -4, "FIELD", null));
        tiles.add(new RelativeTile(7, -4, "FIELD", null));
        tiles.add(new RelativeTile(8, -4, "FIELD", null));
        tiles.add(new RelativeTile(9, -4, "FIELD", null));
        tiles.add(new RelativeTile(10, -4, "FIELD", null));
        tiles.add(new RelativeTile(-9, -5, "FIELD", null));
        tiles.add(new RelativeTile(-8, -5, "FIELD", null));
        tiles.add(new RelativeTile(-7, -5, "FIELD", null));
        tiles.add(new RelativeTile(-6, -5, "FIELD", null));
        tiles.add(new RelativeTile(-5, -5, "FIELD", null));
        tiles.add(new RelativeTile(-4, -5, "FIELD", null));
        tiles.add(new RelativeTile(-3, -5, "FIELD", null));
        tiles.add(new RelativeTile(-2, -5, "FIELD", null));
        tiles.add(new RelativeTile(2, -5, "FIELD", null));
        tiles.add(new RelativeTile(3, -5, "FIELD", null));
        tiles.add(new RelativeTile(4, -5, "FIELD", null));
        tiles.add(new RelativeTile(5, -5, "FIELD", null));
        tiles.add(new RelativeTile(6, -5, "FIELD", null));
        tiles.add(new RelativeTile(7, -5, "FIELD", null));
        tiles.add(new RelativeTile(8, -5, "FIELD", null));
        tiles.add(new RelativeTile(9, -5, "FIELD", null));
        tiles.add(new RelativeTile(-8, -6, "FIELD", null));
        tiles.add(new RelativeTile(-7, -6, "FIELD", null));
        tiles.add(new RelativeTile(-6, -6, "FIELD", null));
        tiles.add(new RelativeTile(-5, -6, "FIELD", null));
        tiles.add(new RelativeTile(-4, -6, "FIELD", null));
        tiles.add(new RelativeTile(-3, -6, "FIELD", null));
        tiles.add(new RelativeTile(3, -6, "FIELD", null));
        tiles.add(new RelativeTile(4, -6, "FIELD", null));
        tiles.add(new RelativeTile(5, -6, "FIELD", null));
        tiles.add(new RelativeTile(6, -6, "FIELD", null));
        tiles.add(new RelativeTile(7, -6, "FIELD", null));
        tiles.add(new RelativeTile(8, -6, "FIELD", null));
        return new FieldPreset("Gnomeball Field", tiles);
    }

    private static void fillRect(List<RelativeTile> tiles, int minX, int maxX, int minY, int maxY, String tileType)
    {
        for (int x = minX; x <= maxX; x++)
            for (int y = minY; y <= maxY; y++)
                tiles.add(new RelativeTile(x, y, tileType, null));
    }
}
