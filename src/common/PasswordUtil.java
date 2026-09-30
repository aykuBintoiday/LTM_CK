package common;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class PasswordUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH = 256;

    private PasswordUtil() {}

    public static String hash(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
            return "pbkdf2$" + ITERATIONS + "$" +
                    Base64.getUrlEncoder().withoutPadding().encodeToString(salt) + "$" +
                    Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể băm mật khẩu", e);
        }
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$", -1);
            if (p.length != 4 || !"pbkdf2".equals(p[0])) return false;
            int iterations = Integer.parseInt(p[1]);
            byte[] salt = Base64.getUrlDecoder().decode(p[2]);
            byte[] expected = Base64.getUrlDecoder().decode(p[3]);
            byte[] actual = pbkdf2(password.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }
}
