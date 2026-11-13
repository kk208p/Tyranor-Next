package com.core.advhd;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Single identity-to-user-root contract shared by launcher and host. */
public final class AdvhdPaths {
    private AdvhdPaths() {}

    public static File userDataDir(File filesDir, String gameIdentity) throws IOException {
        if (gameIdentity == null || gameIdentity.trim().isEmpty() || gameIdentity.length() > 8192
                || gameIdentity.indexOf('\0') >= 0) throw new IOException("Invalid game identity");
        for (int i = 0; i < gameIdentity.length(); i++) {
            char c = gameIdentity.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= gameIdentity.length() || !Character.isLowSurrogate(gameIdentity.charAt(i)))
                    throw new IOException("Invalid game identity UTF-16");
            } else if (Character.isLowSurrogate(c)) throw new IOException("Invalid game identity UTF-16");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(gameIdentity.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b & 255) >>> 4, 16));
                hex.append(Character.forDigit(b & 15, 16));
            }
            return new File(new File(new File(filesDir.getCanonicalFile(), "advhd"), "userdata"), hex.toString());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Optional translation overlay must live under the private translation root. */
    public static String validatedTranslation(File filesDir, String suppliedPath) throws IOException {
        if (suppliedPath == null || suppliedPath.trim().isEmpty()) return "";
        if (suppliedPath.indexOf('\0') >= 0 || suppliedPath.length() > 8192) throw new IOException("Invalid translation path");
        File root = new File(new File(filesDir.getCanonicalFile(), "advhd"), "translations").getCanonicalFile();
        File file = new File(suppliedPath).getCanonicalFile();
        if (!file.toPath().startsWith(root.toPath()) || !file.isFile() || !file.canRead()
                || file.length() == 0 || file.length() > 8L * 1024 * 1024
                || !file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            throw new IOException("Translation policy");
        }
        return file.getAbsolutePath();
    }

    /** Exact spelling and canonical destination must both match; no aliases or symlink redirection. */
    public static File validateUserDataDir(File filesDir, String gameIdentity, String suppliedPath) throws IOException {
        if (suppliedPath == null || suppliedPath.indexOf('\0') >= 0 || suppliedPath.length() > 8192)
            throw new IOException("Invalid user path");
        File expected = userDataDir(filesDir, gameIdentity);
        File supplied = new File(suppliedPath);
        if (!supplied.isAbsolute() || !supplied.getAbsolutePath().equals(expected.getAbsolutePath())
                || !supplied.getCanonicalFile().equals(expected)) throw new IOException("Identity/user-root mismatch");
        return expected;
    }
}
