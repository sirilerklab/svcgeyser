package com.sirilerklab.svcgeyser.network;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

/**
 * Loads or creates the TLS material for the bridge WebSocket (WSS).
 *
 * On first run it generates a self-signed RSA certificate, stores it in a PKCS12 keystore
 * ({@code cert.p12}) inside the plugin data folder, and exports the public certificate to
 * {@code cert.pem} and the private key to {@code cert.key} (PEM). Subsequent runs reuse the
 * existing keystore. The certificate is self-signed, so the Android client trusts it
 * explicitly (it does not chain to a public CA).
 */
public final class CertManager {

    private static final String ALIAS = "svcgeyser";
    private static final String KEYSTORE_FILE = "cert.p12";
    private static final String CERT_PEM = "cert.pem";
    private static final String KEY_PEM = "cert.key";

    private CertManager() {}

    /** Loads the PKCS12 keystore (creating it on first run) and returns a server SSLContext. */
    public static SSLContext loadOrCreate(File dataFolder, String keystorePassword) throws Exception {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IOException("Could not create plugin data folder: " + dataFolder);
        }
        char[] pw = keystorePassword.toCharArray();
        File p12 = new File(dataFolder, KEYSTORE_FILE);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        if (p12.exists()) {
            try (FileInputStream in = new FileInputStream(p12)) {
                ks.load(in, pw);
            }
        } else {
            ks = generate(dataFolder, p12, pw);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    /** Generates a self-signed cert, writes the PKCS12 keystore + cert.pem + cert.key. */
    private static KeyStore generate(File dataFolder, File p12, char[] pw) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair keyPair = kpg.generateKeyPair();

        X500Name dn = new X500Name("CN=SVCGeyser");
        Instant now = Instant.now();
        Date notBefore = Date.from(now.minus(1, ChronoUnit.DAYS));
        Date notAfter = Date.from(now.plus(3650, ChronoUnit.DAYS));
        BigInteger serial = new BigInteger(64, new SecureRandom());

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                dn, serial, notBefore, notAfter, dn, keyPair.getPublic());
        // Subject Alternative Names — harmless extras; the client skips hostname verification.
        GeneralNames san = new GeneralNames(new GeneralName[]{
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1"),
        });
        builder.addExtension(Extension.subjectAlternativeName, false, san);

        // Sign and convert using the default JDK providers (no BouncyCastle provider needs to
        // be registered, which keeps shading/relocation of BouncyCastle straightforward).
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(holder);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(ALIAS, keyPair.getPrivate(), pw, new Certificate[]{cert});
        try (FileOutputStream out = new FileOutputStream(p12)) {
            ks.store(out, pw);
        }

        writePem(new File(dataFolder, CERT_PEM), "CERTIFICATE", cert.getEncoded());
        writePem(new File(dataFolder, KEY_PEM), "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        return ks;
    }

    private static void writePem(File file, String type, byte[] der) throws IOException {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        String pem = "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
        Files.writeString(file.toPath(), pem);
    }
}
