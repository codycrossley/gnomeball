package gay.runescape.gnomeball;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.runelite.api.coords.WorldPoint;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TileReducer
{
    /** Referee-placed "go here" marker (see FlagRenderer) -- rides the same mark/unmark sync as
     * every other tile type, but is never part of the field's footprint, so anything that treats
     * the tile set as "the field" (saving a custom slot, Clear Arena) should use
     * {@link #fieldSnapshot()} instead of {@link #snapshot()}. */
    public static final String FLAG = "FLAG";

    public static final class TileEntry
    {
        public final WorldPoint point;
        public final String tileType;
        public final String color;
        public final Integer orientation; // Jagex Angle Units, 0-2047 -- GOALPOST_A/B only, else null

        public TileEntry(WorldPoint point, String tileType, String color, Integer orientation)
        {
            this.point = point;
            this.tileType = tileType;
            this.color = color;
            this.orientation = orientation;
        }
    }

    private final ConcurrentHashMap<String, TileEntry> tiles = new ConcurrentHashMap<>();

    // Bumped on every mutation below -- lets a per-frame renderer (TileOverlay) cache whatever it
    // derives from the committed tile set (grouping/connectivity) and only recompute when this
    // actually changes, instead of rebuilding it from scratch on every single render() call.
    private volatile int version = 0;

    public int version()
    {
        return version;
    }

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
        Integer orientation = safeInt(tile, "orientation");

        tiles.put(key(x, y, plane, tileType),
            new TileEntry(new WorldPoint(x, y, plane), tileType, color, orientation));
        version++;
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
        version++;
    }

    private static JsonArray asArray(JsonObject o, String k)
    {
        return (o != null && o.has(k) && o.get(k).isJsonArray()) ? o.get(k).getAsJsonArray() : new JsonArray();
    }

    public void loadAll(List<ApiClient.TileOut> tileList)
    {
        if (tileList == null) return;
        tiles.clear();
        for (ApiClient.TileOut t : tileList)
        {
            if (t == null || t.tileType == null) continue; // server always requires/validates a real tileType
            tiles.put(key(t.x, t.y, t.plane, t.tileType),
                new TileEntry(new WorldPoint(t.x, t.y, t.plane), t.tileType, t.color, t.orientation));
        }
        version++;
    }

    public void reset()
    {
        tiles.clear();
        version++;
    }

    public List<TileEntry> snapshot()
    {
        return Collections.unmodifiableList(new ArrayList<>(tiles.values()));
    }

    /** Every tile except FLAG markers -- the field/zone/cheerleader/goalpost layout on its own. */
    public List<TileEntry> fieldSnapshot()
    {
        List<TileEntry> result = new ArrayList<>();
        for (TileEntry e : tiles.values())
        {
            if (!FLAG.equals(e.tileType)) result.add(e);
        }
        return Collections.unmodifiableList(result);
    }

    public List<WorldPoint> flagPoints()
    {
        List<WorldPoint> result = new ArrayList<>();
        for (TileEntry e : tiles.values())
        {
            if (FLAG.equals(e.tileType)) result.add(e.point);
        }
        return result;
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
        String flagKey = prefix + FLAG;
        for (String k : tiles.keySet())
        {
            // A flag isn't a field marking -- a flag-only tile should still offer the host "Mark
            // Tile", not "Edit Tile"/"Unmark All".
            if (k.startsWith(prefix) && !k.equals(flagKey)) return true;
        }
        return false;
    }

    /** Whether {@code wp} itself is marked FIELD, ZONE_A, ZONE_B, GOALPOST_A, or GOALPOST_B — an
     * exact per-tile check, not an approximation, since those tile types now cover the field's
     * actual footprint. GOALPOST_A/GOALPOST_B behave identically to ZONE_A/ZONE_B here — they're
     * a zone with a 3D model standing on it, not a separate footprint concept. */
    public boolean isWithinField(WorldPoint wp)
    {
        return hasMarker(wp, "FIELD") || hasMarker(wp, "ZONE_A") || hasMarker(wp, "ZONE_B")
            || hasMarker(wp, "GOALPOST_A") || hasMarker(wp, "GOALPOST_B");
    }

    /** Whether the host has marked out an actual FIELD boundary -- specifically the "FIELD" tile
     * type, not just any ZONE_A/ZONE_B/GOALPOST_A/GOALPOST_B footprint. Out-of-bounds detection
     * needs this guard: without a drawn boundary there's no "outside" to have stepped out of, so a
     * host who's only placed zones/goalposts (no surrounding FIELD) gets a boundary-free setup
     * where a team can score from anywhere on the map, rather than every non-zone tile on earth
     * silently counting as "out of bounds". */
    public boolean hasFieldBoundary()
    {
        for (TileEntry e : tiles.values())
        {
            if ("FIELD".equals(e.tileType)) return true;
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
