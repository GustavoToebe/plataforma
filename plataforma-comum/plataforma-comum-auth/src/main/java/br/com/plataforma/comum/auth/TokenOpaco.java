package br.com.plataforma.comum.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Token opaco de 256 bits. O banco guarda só o SHA-256, para a busca por hash ser determinística. */
public final class TokenOpaco {

    private static final SecureRandom ALEATORIO = new SecureRandom();

    private TokenOpaco() {
    }

    public static String gerar() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String bruto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bruto.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 não disponível na JVM", e);
        }
    }
}
