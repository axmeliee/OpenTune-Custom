package android.net;

/** See [ConnectivityManager]. */
public final class NetworkCapabilities {
    public static final int NET_CAPABILITY_NOT_METERED = 11;

    private NetworkCapabilities() {}

    public boolean hasCapability(int capability) { return true; }
}
