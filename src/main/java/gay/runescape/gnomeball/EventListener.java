package gay.runescape.gnomeball;

public interface EventListener
{
    void onEvent(ApiClient.EventOut e);
    void onError(Exception e);
}
