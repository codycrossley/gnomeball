package gay.runescape.gnomeball;

import com.google.gson.JsonObject;
import net.runelite.api.coords.WorldPoint;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TileReducer
{
    public static final class TileEntry
    {
        public final WorldPoint point;
        public final String tileType;
        public final String color;

        public TileEntry(WorldPoint point, String tileType, String color)
        {
            this.point = point;
            this.tileType = tileType;
            this.color = color;
        }
    }

    private final ConcurrentHashMap<String, TileEntry> tiles = new ConcurrentHashMap<>();

    // Cache of per-plane field bounding boxes, since isWithinField() is called from a hot
    // render loop (once per observer per frame) and boundary tiles rarely change between calls.
    private static final int[] NO_FIELD = new int[0];
    private final ConcurrentHashMap<Integer, int[]> fieldBoundsCache = new ConcurrentHashMap<>();

    public void apply(ApiClient.EventOut e)
    {
        if (e == null || e.type == null) return;
        String type = e.type.toUpperCase(Locale.ROOT);

        if ("TILE_MARKED".equals(type))
        {
            Integer x = safeInt(e.payload, "x");
            Integer y = safeInt(e.payload, "y");
            Integer plane = safeInt(e.payload, "plane");
            if (x == null || y == null || plane == null) return;

            String tileType = safeStr(e.payload, "tileType");
            if (tileType == null) tileType = "STANDARD";
            String color = safeStr(e.payload, "color");

            tiles.put(key(x, y, plane, tileType),
                new TileEntry(new WorldPoint(x, y, plane), tileType, color));
            fieldBoundsCache.clear();
        }
        else if ("TILE_UNMARKED".equals(type))
        {
            Integer x = safeInt(e.payload, "x");
            Integer y = safeInt(e.payload, "y");
            Integer plane = safeInt(e.payload, "plane");
            if (x == null || y == null || plane == null) return;

            String tileType = safeStr(e.payload, "tileType");
            if (tileType != null)
            {
                tiles.remove(key(x, y, plane, tileType));
            }
            else
            {
                String prefix = x + ":" + y + ":" + plane + ":";
                tiles.keySet().removeIf(k -> k.startsWith(prefix));
            }
            fieldBoundsCache.clear();
        }
    }

    public void loadAll(List<ApiClient.TileOut> tileList)
    {
        tiles.clear();
        fieldBoundsCache.clear();
        if (tileList == null) return;
        for (ApiClient.TileOut t : tileList)
        {
            if (t == null) continue;
            String tt = t.tileType != null ? t.tileType : "STANDARD";
            tiles.put(key(t.x, t.y, t.plane, tt),
                new TileEntry(new WorldPoint(t.x, t.y, t.plane), tt, t.color));
        }
    }

    public void reset()
    {
        tiles.clear();
        fieldBoundsCache.clear();
    }

    public List<TileEntry> snapshot()
    {
        return Collections.unmodifiableList(new ArrayList<>(tiles.values()));
    }

    public boolean hasMarker(WorldPoint wp, String tileType)
    {
        if (wp == null) return false;
        return tiles.containsKey(key(wp.getX(), wp.getY(), wp.getPlane(), tileType));
    }

    public boolean hasAnyMarker(WorldPoint wp)
    {
        if (wp == null) return false;
        String prefix = wp.getX() + ":" + wp.getY() + ":" + wp.getPlane() + ":";
        for (String k : tiles.keySet())
        {
            if (k.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Whether {@code wp} falls within the axis-aligned bounding box of all placed BOUNDARY_*
     * tiles on its plane. Boundary tiles carry no grouping id, so this treats every marked
     * boundary tile on a given plane as belonging to one combined field.
     */
    public boolean isWithinField(WorldPoint wp)
    {
        if (wp == null) return false;
        int[] bounds = fieldBoundsCache.computeIfAbsent(wp.getPlane(), this::computeFieldBounds);
        if (bounds.length == 0) return false;
        return wp.getX() >= bounds[0] && wp.getX() <= bounds[1]
            && wp.getY() >= bounds[2] && wp.getY() <= bounds[3];
    }

    private int[] computeFieldBounds(int plane)
    {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        boolean found = false;

        for (TileEntry t : tiles.values())
        {
            if (t.point.getPlane() != plane) continue;
            if (!t.tileType.startsWith("BOUNDARY_")) continue;

            found = true;
            minX = Math.min(minX, t.point.getX());
            maxX = Math.max(maxX, t.point.getX());
            minY = Math.min(minY, t.point.getY());
            maxY = Math.max(maxY, t.point.getY());
        }

        return found ? new int[] { minX, maxX, minY, maxY } : NO_FIELD;
    }

    private static String key(int x, int y, int plane, String tileType)
    {
        return x + ":" + y + ":" + plane + ":" + tileType;
    }

    private static String safeStr(JsonObject o, String k)
    {
        return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null;
    }

    private static Integer safeInt(JsonObject o, String k)
    {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsInt() : null; }
        catch (Exception ignored) { return null; }
    }
}
