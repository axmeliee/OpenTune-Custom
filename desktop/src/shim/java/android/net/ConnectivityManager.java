package android.net;

/** Never handed out on the desktop ([android.content.Context#getSystemService] returns null); here so the shared code compiles. */
public final class ConnectivityManager {
    private ConnectivityManager() {}

    public Network getActiveNetwork() { return null; }

    public NetworkCapabilities getNetworkCapabilities(Network network) { return null; }
}
