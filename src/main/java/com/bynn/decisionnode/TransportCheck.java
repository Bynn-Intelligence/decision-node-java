package com.bynn.decisionnode;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.ECGenParameterSpec;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;

/**
 * Checks on the transport a client sends with, made once when the client is built.
 *
 * <ul>
 *   <li>A caller's {@link HttpClient} must be the JDK's own implementation and must not follow
 *       redirects. A client of any other type cannot be shown to refuse redirects, so it is
 *       treated as one that follows them.
 *   <li>For an {@code https} base URL, TLS certificate and hostname checks must be on. A
 *       caller's {@link SSLContext} other than the JDK default is tested with an in-memory TLS
 *       handshake against a throwaway self-signed certificate made for the test: a context that
 *       accepts that certificate, or one whose answer is not a clear refusal, is refused. JDK
 *       hostname verification turned off through
 *       {@code jdk.internal.httpclient.disableHostnameVerification} is refused too.
 * </ul>
 */
final class TransportCheck {
    static final String HOSTNAME_PROPERTY = "jdk.internal.httpclient.disableHostnameVerification";

    /** Contexts already shown to verify certificates; a context is tested once. */
    private static final Map<SSLContext, Boolean> VERIFIED = Collections.synchronizedMap(new WeakHashMap<>());

    private static volatile SSLContext probeServer;

    private TransportCheck() {}

    /** Refuses a caller's client that cannot be shown to refuse redirects. */
    static void checkRedirects(HttpClient client) {
        if (client.getClass().getModule() != HttpClient.class.getModule()) {
            // A subclass can answer followRedirects() with anything; only the JDK's own client
            // is known to do what it says.
            throw new IllegalArgumentException("the HttpClient must be one made by HttpClient.newBuilder() or "
                    + "HttpClient.newHttpClient(); a client of type " + client.getClass().getName()
                    + " cannot be shown to refuse redirects");
        }
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            // A redirect would carry the API key to another URL, possibly over plain HTTP.
            throw new IllegalArgumentException("the HttpClient must not follow redirects (HttpClient.Redirect.NEVER)");
        }
    }

    /** Refuses transport settings that turn off TLS certificate or hostname checks. */
    static void checkTls(HttpClient client) {
        if (hostnameVerificationDisabled()) {
            throw new IllegalArgumentException("TLS hostname verification is turned off (system property "
                    + HOSTNAME_PROPERTY + "); an https base URL needs it on");
        }
        if (client == null) {
            return;
        }
        SSLContext ctx = client.sslContext();
        SSLContext dflt;
        try {
            dflt = SSLContext.getDefault();
        } catch (GeneralSecurityException e) {
            dflt = null;
        }
        if (ctx == null || ctx == dflt || VERIFIED.containsKey(ctx)) {
            return;
        }
        if (!refusesUnknownCertificate(ctx)) {
            throw new IllegalArgumentException("the HttpClient's SSLContext accepts a certificate no authority "
                    + "signed, or could not be shown to refuse one; TLS certificate checks must stay on");
        }
        VERIFIED.put(ctx, Boolean.TRUE);
    }

    /** The JDK HTTP client reads the property as a flag: present and not "false" means off. */
    static boolean hostnameVerificationDisabled() {
        String v = System.getProperty(HOSTNAME_PROPERTY);
        if (v == null) {
            return false;
        }
        return v.isEmpty() || Boolean.parseBoolean(v);
    }

    /**
     * True when a handshake from {@code client} to a server presenting a fresh self-signed
     * certificate fails in the client because the certificate is not trusted.
     */
    static boolean refusesUnknownCertificate(SSLContext client) {
        try {
            SSLEngine c = client.createSSLEngine("tls-check.invalid", 443);
            c.setUseClientMode(true);
            SSLEngine s = server().createSSLEngine();
            s.setUseClientMode(false);
            int packet = Math.max(c.getSession().getPacketBufferSize(), s.getSession().getPacketBufferSize());
            int app = Math.max(c.getSession().getApplicationBufferSize(), s.getSession().getApplicationBufferSize());
            ByteBuffer toServer = ByteBuffer.allocate(packet * 4);
            ByteBuffer toClient = ByteBuffer.allocate(packet * 4);
            ByteBuffer clientApp = ByteBuffer.allocate(app);
            ByteBuffer serverApp = ByteBuffer.allocate(app);
            ByteBuffer empty = ByteBuffer.allocate(0);
            c.beginHandshake();
            s.beginHandshake();
            for (int round = 0; round < 100; round++) {
                try {
                    c.wrap(empty, toServer);
                    tasks(c);
                    toClient.flip();
                    c.unwrap(toClient, clientApp);
                    toClient.compact();
                    tasks(c);
                } catch (SSLException e) {
                    return causedByCertificate(e);
                }
                try {
                    s.wrap(empty, toClient);
                    tasks(s);
                    toServer.flip();
                    s.unwrap(toServer, serverApp);
                    toServer.compact();
                    tasks(s);
                } catch (SSLException e) {
                    // The server failed first: no clear answer from the client.
                    return false;
                }
                if (done(c) && done(s)) {
                    return false; // the client trusted the throwaway certificate
                }
            }
            return false;
        } catch (GeneralSecurityException | SSLException | RuntimeException e) {
            return false;
        }
    }

    private static boolean done(SSLEngine e) {
        SSLEngineResult.HandshakeStatus h = e.getHandshakeStatus();
        return h == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING || h == SSLEngineResult.HandshakeStatus.FINISHED;
    }

    private static void tasks(SSLEngine e) {
        Runnable t;
        while ((t = e.getDelegatedTask()) != null) {
            t.run();
        }
    }

    private static boolean causedByCertificate(Throwable t) {
        for (int i = 0; t != null && i < 16; i++, t = t.getCause()) {
            if (t instanceof CertificateException || t instanceof java.security.cert.CertPathValidatorException
                    || t instanceof java.security.cert.CertPathBuilderException) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the test server

    private static SSLContext server() throws GeneralSecurityException {
        SSLContext s = probeServer;
        if (s == null) {
            synchronized (TransportCheck.class) {
                if (probeServer == null) {
                    probeServer = makeServer();
                }
                s = probeServer;
            }
        }
        return s;
    }

    private static SSLContext makeServer() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        KeyPair kp = g.generateKeyPair();
        byte[] der = selfSigned(kp);
        Certificate cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(der));
        char[] pw = "tls-check".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try {
            ks.load(null, null);
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException(e);
        }
        ks.setKeyEntry("tls-check", kp.getPrivate(), pw, new Certificate[] {cert});
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    /** A minimal X.509 v1 certificate for {@code kp}, signed by itself with ECDSA over SHA-256. */
    private static byte[] selfSigned(KeyPair kp) throws GeneralSecurityException {
        byte[] ecdsaSha256 = tlv(0x30, tlv(0x06, new byte[] {0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02}));
        byte[] name = tlv(0x30, tlv(0x31, tlv(0x30, cat(
                tlv(0x06, new byte[] {0x55, 0x04, 0x03}),
                tlv(0x0C, "decisionnode tls check".getBytes(StandardCharsets.UTF_8))))));
        DateTimeFormatter utc = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'");
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        byte[] validity = tlv(0x30, cat(
                tlv(0x17, utc.format(now.minusDays(1)).getBytes(StandardCharsets.US_ASCII)),
                tlv(0x17, utc.format(now.plusDays(1)).getBytes(StandardCharsets.US_ASCII))));
        byte[] serial = tlv(0x02, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] tbs = tlv(0x30, cat(serial, ecdsaSha256, name, validity, name, kp.getPublic().getEncoded()));
        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(kp.getPrivate());
        sig.update(tbs);
        byte[] signature = sig.sign();
        return tlv(0x30, cat(tbs, ecdsaSha256, tlv(0x03, cat(new byte[] {0}, signature))));
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int n = value.length;
        if (n < 0x80) {
            out.write(n);
        } else if (n < 0x100) {
            out.write(0x81);
            out.write(n);
        } else {
            out.write(0x82);
            out.write(n >> 8);
            out.write(n & 0xFF);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
