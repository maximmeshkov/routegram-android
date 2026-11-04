package com.routegram.wsproxy;

import android.test.AndroidTestCase;
import android.util.Log;
import com.routegram.core.ProxyConfig;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Runs inside an Android app UID, including real upstream protocol traffic. */
public class FlowsealRuntimeTest extends AndroidTestCase {
    @Override protected void tearDown() throws Exception {
        WsProxyNative.INSTANCE.stop();
        super.tearDown();
    }

    public void testRuntimeAndCrypto() throws Exception {
        JSONObject info = WsProxyNative.INSTANCE.diagnostics(getContext());
        Log.i("RoutegramTest", info.toString());
        JSONObject pins;
        try (java.util.Scanner source = new java.util.Scanner(
                getContext().getAssets().open("routegram-python/upstream.json"), "UTF-8")) {
            pins = new JSONObject(source.useDelimiter("\\A").next());
        }
        assertEquals(pins.getJSONObject("flowseal").getString("version"), info.getString("flowseal"));
        assertTrue(info.getString("python").startsWith(pins.getJSONObject("python").getString("version")));
        assertEquals("66e94bd4ef8a2c3b884cfa59ca342b2e", info.getString("aes"));
    }

    public void testOccupiedPortAndRestart() throws Exception {
        WsProxyNative.INSTANCE.stop();
        try (ServerSocket occupied = new ServerSocket(1443, 1, InetAddress.getByName("127.0.0.1"))) {
            try {
                WsProxyNative.INSTANCE.start(getContext(), new WsProxySettings());
                fail("An occupied port must fail, without taking over the other listener");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().length() > 0);
            }
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            ProxyConfig config = WsProxyNative.INSTANCE.start(getContext(), new WsProxySettings());
            try (Socket socket = new Socket(config.getAddress(), config.getPort())) {
                assertTrue(socket.isConnected());
            }
            WsProxyNative.INSTANCE.stop();
            try (ServerSocket released = new ServerSocket(1443, 1, InetAddress.getByName("127.0.0.1"))) {
                assertEquals(1443, released.getLocalPort());
            }
        }
    }

    public void testTelegramHandshakeThroughProxy() throws Exception {
        telegramHandshake(WsProxyNative.INSTANCE.start(getContext(), new WsProxySettings()));
    }

    /** Diagnostic probe on a separate port: the installed client can keep running. */
    public void testIndependentRoute() throws Exception {
        for (String endpoint : new String[] {"https://www.google.com/generate_204",
                "https://connectivitycheck.gstatic.com/generate_204"}) {
            long started = android.os.SystemClock.elapsedRealtime();
            java.net.HttpURLConnection connection = null;
            try {
                connection = (java.net.HttpURLConnection) new java.net.URL(endpoint).openConnection();
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);
                connection.setInstanceFollowRedirects(false);
                Log.i("RoutegramTest", "Connectivity probe " + endpoint + ": HTTP " + connection.getResponseCode());
            } catch (java.io.IOException error) {
                Log.i("RoutegramTest", "Connectivity probe " + endpoint + ": " + error.getClass().getSimpleName());
            } finally {
                if (connection != null) connection.disconnect();
                Log.i("RoutegramTest", "Connectivity probe elapsed: " +
                        (android.os.SystemClock.elapsedRealtime() - started) + " ms");
            }
        }
        telegramHandshake(WsProxyNative.INSTANCE.start(getContext(),
            new WsProxySettings("127.0.0.1", 1444, "", 4, true, "", "", false)));
    }

    public void testTelegramWebSocketHandshake() throws Exception {
        String ip = null;
        for (InetAddress address : InetAddress.getAllByName("kws2.web.telegram.org")) {
            if (address instanceof java.net.Inet4Address) { ip = address.getHostAddress(); break; }
        }
        assertNotNull("Telegram WebSocket endpoint needs an IPv4 address", ip);
        ProxyConfig config = WsProxyNative.INSTANCE.start(getContext(),
            new WsProxySettings("127.0.0.1", 1443, "2:" + ip, 4, false, "", "", true));
        // Upstream opens its listener before its asynchronous WS pool is warm.
        long deadline = android.os.SystemClock.elapsedRealtime() + 20000;
        JSONObject before;
        do {
            before = WsProxyNative.INSTANCE.diagnostics(getContext());
            if (before.getJSONObject("ws_pool_ready").optInt("2") > 0) break;
            Thread.sleep(100);
        } while (android.os.SystemClock.elapsedRealtime() < deadline);
        assertTrue("WebSocket pool did not become ready: " + before,
            before.getJSONObject("ws_pool_ready").optInt("2") > 0);
        telegramHandshake(config);
        JSONObject after = WsProxyNative.INSTANCE.diagnostics(getContext());
        assertEquals(before.getLong("connections_ws") + 1, after.getLong("connections_ws"));
        assertEquals("WebSocket test must not use TCP fallback",
            before.getLong("connections_tcp_fallback"), after.getLong("connections_tcp_fallback"));
    }

    private void telegramHandshake(ProxyConfig config) throws Exception {
        byte[] secret = hex(config.getSecret().substring(2));
        SecureRandom random = new SecureRandom();
        byte[] hello = new byte[64];
        random.nextBytes(hello);
        hello[0] = 0x45; // Avoid HTTP/TLS/transport reserved prefixes.
        Arrays.fill(hello, 56, 60, (byte) 0xdd); // Padded intermediate.
        hello[60] = 2; hello[61] = 0; // Telegram DC2.
        byte[] reversed = new byte[64];
        for (int i = 0; i < 64; i++) reversed[i] = hello[63 - i];
        Cipher outgoing = cipher(hello, secret);
        Cipher incoming = cipher(reversed, secret);
        byte[] encryptedHello = outgoing.update(hello);
        System.arraycopy(encryptedHello, 56, hello, 56, 8);

        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        ByteBuffer request = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        request.putInt(40).putLong(0).putLong((System.currentTimeMillis() / 1000) << 32);
        request.putInt(20).putInt(0xbe7e8ef1).put(nonce); // req_pq_multi, no user credentials.
        long started = System.currentTimeMillis();
        try (Socket socket = new Socket(config.getAddress(), config.getPort())) {
            socket.setSoTimeout(30000);
            socket.getOutputStream().write(hello);
            socket.getOutputStream().write(outgoing.update(request.array()));
            int size = ByteBuffer.wrap(incoming.update(read(socket.getInputStream(), 4)))
                .order(ByteOrder.LITTLE_ENDIAN).getInt();
            assertTrue("Invalid response frame length " + size, size >= 40 && size < 65536);
            byte[] response = incoming.update(read(socket.getInputStream(), size));
            assertEquals(0x05162463, ByteBuffer.wrap(response, 20, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            assertTrue("Telegram must echo the request nonce", Arrays.equals(nonce, Arrays.copyOfRange(response, 24, 40)));
        }
        Log.i("RoutegramTest", "Telegram resPQ with matching nonce through Flowseal: " +
            (System.currentTimeMillis() - started) + " ms");
        Log.i("RoutegramTest", "Route counters: " + WsProxyNative.INSTANCE.diagnostics(getContext()));
    }

    private static Cipher cipher(byte[] header, byte[] secret) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        hash.update(header, 8, 32);
        byte[] key = hash.digest(secret);
        Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
            new IvParameterSpec(Arrays.copyOfRange(header, 40, 56)));
        return cipher;
    }

    private static byte[] read(InputStream input, int count) throws Exception {
        byte[] result = new byte[count];
        int offset = 0;
        while (offset < count) {
            int size = input.read(result, offset, count - offset);
            if (size < 0) throw new java.io.EOFException("Proxy closed the connection");
            offset += size;
        }
        return result;
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(value.substring(2*i, 2*i+2), 16);
        return bytes;
    }
}
