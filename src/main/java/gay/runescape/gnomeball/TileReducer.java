package gay.runescape.gnomeball;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

    public void apply(ApiClient.EventOut e)
    {
        if (e == null || e.type == null) return;
        String type = e.type.toUpperCase(Locale.ROOT);

        if ("TILE_MARKED".equals(type))
        {
            applyMark(e.payload);
        }
        else if ("TILE_UNMARKED".equals(type))
        {
            applyUnmark(e.payload);
        }
        else if ("TILES_MARKED".equals(type))
        {
            // Bulk counterpart to TILE_MARKED -- one event carrying a whole preset's worth of
            // tiles (see mark-tiles/commitPreset), so a big preset commit is one event to apply
            // here instead of hundreds, even though each individual tile is applied identically.
            for (JsonElement el : asArray(e.payload, "tiles"))
            {
                if (el.isJsonObject()) applyMark(el.getAsJsonObject());
            }
        }
        else if ("TILES_UNMARKED".equals(type))
        {
            for (JsonElement el : asArray(e.payload, "tiles"))
            {
                if (el.isJsonObject()) applyUnmark(el.getAsJsonObject());
            }
        }
    }

    private void applyMark(JsonObject tile)
    {
        Integer x = safeInt(tile, "x");
        Integer y = safeInt(tile, "y");
        Integer plane = safeInt(tile, "plane");
        if (x == null || y == null || plane == null) return;

        String tileType = safeStr(tile, "tileType");
        if (tileType == null) return; // server always requires/validates a real tileType
        String color = safeStr(tile, "color");

        tiles.put(key(x, y, plane, tileType),
            new TileEntry(new WorldPoint(x, y, plane), tileType, color));
    }

    private void applyUnmark(JsonObject tile)
    {
        Integer x = safeInt(tile, "x");
        Integer y = safeInt(tile, "y");
        Integer plane = safeInt(tile, "plane");
        if (x == null || y == null || plane == null) return;

        String tileType = safeStr(tile, "tileType");
        if (tileType != null)
        {
            tiles.remove(key(x, y, plane, tileType));
        }
        else
        {
            String prefix = x + ":" + y + ":" + plane + ":";
            tiles.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    private static JsonArray asArray(JsonObject o, String k)
    {
        return (o != null && o.has(k) && o.get(k).isJsonArray()) ? o.get(k).getAsJsonArray() : new JsonArray();
    }

    public void loadAll(List<ApiClient.TileOut> tileList)
    {
        tiles.clear();
        if (tileList == null) return;
        for (ApiClient.TileOut t : tileList)
        {
            if (t == null || t.tileType == null) continue; // server always requires/validates a real tileType
            tiles.put(key(t.x, t.y, t.plane, t.tileType),
                new TileEntry(new WorldPoint(t.x, t.y, t.plane), t.tileType, t.color));
        }
    }

    public void reset()
    {
        tiles.clear();
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

    /** Whether {@code wp} itself is marked FIELD, ZONE_A, or ZONE_B — an exact per-tile check,
     * not an approximation, since those tile types now cover the field's actual footprint. */
    public boolean isWithinField(WorldPoint wp)
    {
        return hasMarker(wp, "FIELD") || hasMarker(wp, "ZONE_A") || hasMarker(wp, "ZONE_B");
    }

    /** Whether the host has marked out any field/zone tiles at all. Out-of-bounds detection
     * needs this guard — without it, an unmarked field would mean every position counts as
     * "outside" the (nonexistent) field, firing an out-of-bounds obligation immediately. */
    public boolean hasFieldTiles()
    {
        for (TileEntry e : tiles.values())
        {
            String t = e.tileType;
            if ("FIELD".equals(t) || "ZONE_A".equals(t) || "ZONE_B".equals(t)) return true;
        }
        return false;
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
