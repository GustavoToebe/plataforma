package br.com.plataforma.comum.seguranca;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Texto assinado do contrato v1:
 * {@code MÉTODO\nCAMINHO\nTIMESTAMP\nNONCE\nSHA256_HEX(CORPO)}.
 * O segredo de teste entra em bytes UTF-8; em produção o segredo é Base64.
 */
public final class HmacAssinatura {

    private HmacAssinatura() {
    }

    public static String sha256Hex(byte[] corpo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(corpo));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String assinar(byte[] segredo, String metodo, String caminho, String timestamp, String nonce, byte[] corpo) {
        String texto = metodo.toUpperCase(java.util.Locale.ROOT) +"\n" + caminho + "\n" + timestamp + "\n" + nonce + "\n" + sha256Hex(corpo);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(segredo, "HmacSHA256"));
            String hex = HexFormat.of().formatHex(mac.doFinal(texto.getBytes(StandardCharsets.UTF_8)));
            return "v1=" + hex;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean confere(String esperada, String recebida) {
        if (esperada == null || recebida == null) {
            return false;
        }
        return MessageDigest.isEqual(
                esperada.getBytes(StandardCharsets.UTF_8),
                recebida.getBytes(StandardCharsets.UTF_8));
    }
}
