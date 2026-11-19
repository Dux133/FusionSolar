package pl.fusionsolar.monitor;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * Reads FusionSolar Kiosk data via the public, unauthenticated kiosk endpoint.
 * The URL/token is stored ONLY on the device and never included in app source.
 */
public final class FusionSolarClient {
    private static final Pattern KK = Pattern.compile("(?:[?&])kk=([^&#]+)");
    private static final Pattern CERTIFICATE = Pattern.compile(
            "-----BEGIN CERTIFICATE-----\\s*(.*?)\\s*-----END CERTIFICATE-----", Pattern.DOTALL);
    private static volatile SSLSocketFactory socketFactory;

    private FusionSolarClient() { }

    public static String endpointFromKioskUrl(String kioskUrl) {
        if (kioskUrl == null) throw new IllegalArgumentException("Wprowadź URL FusionSolar Kiosk.");
        Uri uri = Uri.parse(kioskUrl.trim());
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(scheme) || host == null ||
                (!host.equalsIgnoreCase("fusionsolar.huawei.com") &&
                 !host.toLowerCase(java.util.Locale.ROOT).endsWith(".fusionsolar.huawei.com"))) {
            throw new IllegalArgumentException("Adres musi być HTTPS w domenie fusionsolar.huawei.com.");
        }
        // In Huawei URLs the kk parameter normally occurs after '#/kiosk?'.
        Matcher matcher = KK.matcher(kioskUrl);
        if (!matcher.find()) throw new IllegalArgumentException("W URL Kiosk brakuje parametru kk.");
        String token = Uri.decode(matcher.group(1)).trim();
        if (!token.matches("[a-zA-Z0-9_\\-]{4,256}")) {
            throw new IllegalArgumentException("Nieprawidłowy identyfikator kk w URL Kiosk.");
        }
        return "https://" + host + "/rest/pvms/web/kiosk/v1/station-kiosk-file?kk=" + Uri.encode(token);
    }

    public static JSONObject fetch(Context context, String kioskUrl) throws Exception {
        URL url = new URL(endpointFromKioskUrl(kioskUrl));
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(getSocketFactory(context));
        // Keep HTTPS certificate and hostname validation enabled at all times.
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(18000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "FusionSolar-Monitor-Android/1.0");
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                if (code == 403 || code == 401 || code == 404) {
                    throw new Exception("HTTP " + code + ": sprawdź, czy link Kiosk jest nadal ważny.");
                }
                throw new Exception("Serwer FusionSolar zwrócił HTTP " + code + ".");
            }
            byte[] responseBytes = readLimited(conn.getInputStream(), 2_000_000);
            String body = new String(responseBytes, StandardCharsets.UTF_8);
            JSONObject outer;
            try {
                outer = new JSONObject(body);
            } catch (Exception ex) {
                outer = new JSONObject(unescapeHtml(body));
            }
            if (!outer.optBoolean("success", true)) {
                throw new Exception("FusionSolar nie udostępnił danych. Sprawdź link Kiosk.");
            }
            Object payload = outer.opt("data");
            JSONObject data;
            if (payload instanceof JSONObject) {
                data = (JSONObject) payload;
            } else if (payload instanceof String) {
                data = new JSONObject(unescapeHtml((String) payload));
            } else {
                throw new Exception("Nieoczekiwany format odpowiedzi: data.");
            }
            JSONObject realKpi = data.optJSONObject("realKpi");
            if (realKpi == null) throw new Exception("Brak realKpi w odpowiedzi FusionSolar.");
            return realKpi;
        } finally {
            conn.disconnect();
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws Exception {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int count;
            while ((count = in.read(buf)) != -1) {
                if (out.size() + count > limit) throw new Exception("Zbyt duża odpowiedź FusionSolar.");
                out.write(buf, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static String unescapeHtml(String text) {
        // The FusionSolar endpoint sometimes HTML-escapes the JSON string inside 'data'.
        // Decode ampersands last to avoid double-decoding nested escapes.
        return text.replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
    }

    private static SSLSocketFactory getSocketFactory(Context context) throws Exception {
        if (socketFactory != null) return socketFactory;
        synchronized (FusionSolarClient.class) {
            if (socketFactory != null) return socketFactory;
            TrustManagerFactory platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            platform.init((KeyStore) null);
            X509TrustManager platformManager = findX509(platform.getTrustManagers());

            KeyStore extraStore = KeyStore.getInstance(KeyStore.getDefaultType());
            extraStore.load(null, null);
            String pem;
            try (InputStream in = context.getAssets().open("ca-bundle.pem")) {
                pem = new String(readLimited(in, 1_000_000), StandardCharsets.UTF_8);
            }
            Matcher m = CERTIFICATE.matcher(pem);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            int count = 0;
            while (m.find()) {
                try {
                    byte[] der = Base64.decode(m.group(1), Base64.DEFAULT);
                    Certificate cert = factory.generateCertificate(new ByteArrayInputStream(der));
                    extraStore.setCertificateEntry("mozilla-" + count, cert);
                    count++;
                } catch (Exception invalid) {
                    Log.w("FusionSolarMonitor", "Pominięto uszkodzony certyfikat CA", invalid);
                }
            }
            if (count == 0) throw new CertificateException("Nie udało się wczytać certyfikatów CA.");
            TrustManagerFactory extra = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            extra.init(extraStore);
            X509TrustManager extraManager = findX509(extra.getTrustManagers());
            X509TrustManager combined = new CombiningTrustManager(platformManager, extraManager);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{combined}, new SecureRandom());
            socketFactory = ssl.getSocketFactory();
            Log.i("FusionSolarMonitor", "Bezpieczne TLS: dołączono " + count + " certyfikatów CA.");
            return socketFactory;
        }
    }

    private static X509TrustManager findX509(TrustManager[] managers) throws Exception {
        for (TrustManager tm : managers) {
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        }
        throw new CertificateException("Brak systemowego X509TrustManager.");
    }

    private static final class CombiningTrustManager implements X509TrustManager {
        private final X509TrustManager platform;
        private final X509TrustManager mozilla;

        CombiningTrustManager(X509TrustManager platform, X509TrustManager mozilla) {
            this.platform = platform;
            this.mozilla = mozilla;
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            platform.checkClientTrusted(chain, authType);
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                platform.checkServerTrusted(chain, authType);
            } catch (CertificateException ex) {
                mozilla.checkServerTrusted(chain, authType);
            }
        }

        @Override public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = platform.getAcceptedIssuers();
            X509Certificate[] b = mozilla.getAcceptedIssuers();
            X509Certificate[] combined = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, combined, 0, a.length);
            System.arraycopy(b, 0, combined, a.length, b.length);
            return combined;
        }
    }
}
