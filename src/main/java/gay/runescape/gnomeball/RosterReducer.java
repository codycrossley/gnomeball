package gay.runescape.gnomeball;

import com.google.gson.JsonObject;
import net.runelite.client.util.Text;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RosterReducer
{
    private final ConcurrentHashMap<String, GnomeballRole> roleByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> displayNameByPlayer = new ConcurrentHashMap<>();
    private final Set<String> rosterPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Boolean> actuallyJoined = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> onlineByPlayer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> numberByPlayer = new ConcurrentHashMap<>();

    public static final class RosterEntry
    {
        public final String rsn;
        public final GnomeballRole role;
        public final boolean online;
        public final String number;
        public final boolean joined;

        public RosterEntry(String rsn, GnomeballRole role, boolean online, String number, boolean joined)
        {
            this.rsn = rsn;
            this.role = role;
            this.online = online;
            this.number = number;
            this.joined = joined;
        }
    }

    public GnomeballRole getRole(String canonicalRsn)
    {
        if (canonicalRsn == null) return null;
        return roleByPlayer.get(canonicalRsn.toLowerCase(Locale.ROOT));
    }

    public String getNumber(String canonicalRsn)
    {
        if (canonicalRsn == null) return "";
        return numberByPlayer.getOrDefault(canonicalRsn.toLowerCase(Locale.ROOT), "");
    }

    public boolean isOnline(String canonicalRsn)
    {
        if (canonicalRsn == null) return false;
        return Boolean.TRUE.equals(onlineByPlayer.get(canonicalRsn.toLowerCase(Locale.ROOT)));
    }

    public void syncFromRoster(List<ApiClient.RosterPlayerOut> players)
    {
        if (players == null) return;
        for (ApiClient.RosterPlayerOut p : players)
        {
            if (p == null || p.rsn == null) continue;
            String key = p.rsn.toLowerCase(Locale.ROOT);
            onlineByPlayer.put(key, p.online);
            if (p.number != null) numberByPlayer.put(key, p.number);
            actuallyJoined.put(key, p.joined);
        }
    }

    public boolean hasJoined(String canonicalRsn)
    {
        if (canonicalRsn == null) return false;
        return rosterPlayers.contains(canonicalRsn.toLowerCase(Locale.ROOT));
    }

    public int countRole(GnomeballRole role)
    {
        int n = 0;
        for (String key : rosterPlayers)
        {
            if (roleByPlayer.getOrDefault(key, GnomeballRole.OBSERVER) == role) n++;
        }
        return n;
    }

    public void reset()
    {
        roleByPlayer.clear();
        displayNameByPlayer.clear();
        rosterPlayers.clear();
        actuallyJoined.clear();
        onlineByPlayer.clear();
        numberByPlayer.clear();
    }

    public void loadSnapshot(List<ApiClient.RosterPlayerOut> players)
    {
        reset();
        if (players == null) return;
        for (ApiClient.RosterPlayerOut p : players)
        {
            if (p == null || p.rsn == null) continue;
            String key = canonicalKey(p.rsn);
            if (key == null) continue;
            displayNameByPlayer.put(key, displayName(p.rsn));
            rosterPlayers.add(key);
            actuallyJoined.put(key, p.joined);
            if (p.number != null) numberByPlayer.put(key, p.number);
            GnomeballRole role = GnomeballRole.OBSERVER;
            if (p.role != null)
            {
                try { role = GnomeballRole.valueOf(p.role.trim().toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException ignored) {}
            }
            roleByPlayer.put(key, role);
        }
    }

    public void apply(ApiClient.EventOut e)
    {
        if (e == null || e.type == null) return;
        final String type = e.type.toUpperCase(Locale.ROOT);

        switch (type)
        {
            case "PLAYER_JOINED":
            {
                String playerRaw = safeStr(e.payload, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                displayNameByPlayer.putIfAbsent(key, displayName(playerRaw));
                rosterPlayers.add(key);
                actuallyJoined.put(key, true);
                roleByPlayer.putIfAbsent(key, GnomeballRole.OBSERVER);
                break;
            }
            case "ROLE_ASSIGNED":
            {
                String playerRaw = safeStr(e.payload, "player");
                String roleRaw = safeStr(e.payload, "role");
                if (playerRaw == null || roleRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                displayNameByPlayer.putIfAbsent(key, displayName(playerRaw));
                rosterPlayers.add(key);
                actuallyJoined.putIfAbsent(key, false);
                try
                {
                    roleByPlayer.put(key, GnomeballRole.valueOf(roleRaw.trim().toUpperCase(Locale.ROOT)));
                }
                catch (IllegalArgumentException ignored) {}
                break;
            }
            case "PLAYER_LEFT":
            {
                String playerRaw = safeStr(e.payload, "player");
                if (playerRaw == null) return;
                String key = canonicalKey(playerRaw);
                if (key == null) return;
                roleByPlayer.remove(key);
                displayNameByPlayer.remove(key);
                rosterPlayers.remove(key);
                actuallyJoined.remove(key);
                numberByPlayer.remove(key);
                break;
            }
        }
    }

    public List<RosterEntry> snapshot()
    {
        List<RosterEntry> out = new ArrayList<>();
        for (String key : rosterPlayers)
        {
            GnomeballRole role = roleByPlayer.getOrDefault(key, GnomeballRole.OBSERVER);
            String display = displayNameByPlayer.getOrDefault(key, key);
            boolean online = Boolean.TRUE.equals(onlineByPlayer.get(key));
            String number = numberByPlayer.getOrDefault(key, "");
            boolean joined = Boolean.TRUE.equals(actuallyJoined.get(key));
            out.add(new RosterEntry(display, role, online, number, joined));
        }
        out.sort((a, b) ->
        {
            int oa = roleOrder(a.role);
            int ob = roleOrder(b.role);
            if (oa != ob) return Integer.compare(oa, ob);
            if (!a.number.isEmpty() && !b.number.isEmpty()) return a.number.compareTo(b.number);
            return a.rsn.compareToIgnoreCase(b.rsn);
        });
        return out;
    }

    private static int roleOrder(GnomeballRole role)
    {
        switch (role)
        {
            case REFEREE:  return 0;
            case TEAM_A:   return 1;
            case TEAM_B:   return 2;
            case OBSERVER: return 3;
            default:       return 4;
        }
    }

    private static String canonicalKey(String raw)
    {
        if (raw == null) return null;
        String s = Text.removeTags(raw)
            .replaceFirst("\\s*\\(level\\s*-?\\s*\\d+\\)\\s*$", "");
        String canon = Text.toJagexName(s);
        if (canon == null || canon.isBlank()) return null;
        return canon.toLowerCase(Locale.ROOT);
    }

    private static String displayName(String raw)
    {
        if (raw == null) return null;
        String s = Text.removeTags(raw)
            .replaceFirst("\\s*\\(level\\s*-?\\s*\\d+\\)\\s*$", "");
        String canon = Text.toJagexName(s);
        return (canon != null && !canon.isBlank()) ? canon : s.trim();
    }

    private static String safeStr(JsonObject o, String key)
    {
        return (o != null && o.has(key) && !o.get(key).isJsonNull())
            ? o.get(key).getAsString()
            : null;
    }

    private static int safeInt(JsonObject o, String key)
    {
        try { return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : 0; }
        catch (Exception ignored) { return 0; }
    }
}