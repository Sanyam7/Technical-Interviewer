package com.techinterviewer.coding.sandbox;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A minimal POSIX ustar writer: plain files, mode 0644, owned by nobody. The sources reach the container as this stream
 * on standard input ({@code tar -xf -}), so no host directory is ever mounted into it.
 */
final class TarArchive {

    private static final int BLOCK = 512;

    private TarArchive() {
    }

    static byte[] of(List<SandboxJob.SandboxFile> files) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (SandboxJob.SandboxFile file : files) {
            out.writeBytes(header(file.name(), file.content().length));
            out.writeBytes(file.content());
            int padding = (BLOCK - file.content().length % BLOCK) % BLOCK;
            out.writeBytes(new byte[padding]);
        }
        out.writeBytes(new byte[BLOCK * 2]);
        return out.toByteArray();
    }

    static byte[] header(String name, long size) {
        byte[] name8 = name.getBytes(StandardCharsets.UTF_8);
        if (name8.length > 100) {
            throw new IllegalArgumentException("tar entry name too long: " + name);
        }
        byte[] header = new byte[BLOCK];
        System.arraycopy(name8, 0, header, 0, name8.length);
        put(header, 100, octal(0644, 7));
        put(header, 108, octal(65534, 7));
        put(header, 116, octal(65534, 7));
        put(header, 124, octal(size, 11));
        put(header, 136, octal(0, 11));
        for (int i = 148; i < 156; i++) {
            header[i] = ' ';
        }
        header[156] = '0';
        put(header, 257, "ustar\0".getBytes(StandardCharsets.US_ASCII));
        put(header, 263, "00".getBytes(StandardCharsets.US_ASCII));
        long checksum = 0;
        for (byte b : header) {
            checksum += b & 0xff;
        }
        put(header, 148, octal(checksum, 6));
        header[154] = 0;
        header[155] = ' ';
        return header;
    }

    private static byte[] octal(long value, int digits) {
        String text = Long.toOctalString(value);
        if (text.length() > digits) {
            throw new IllegalArgumentException("value " + value + " does not fit " + digits + " octal digits");
        }
        return ("0".repeat(digits - text.length()) + text + "\0").getBytes(StandardCharsets.US_ASCII);
    }

    private static void put(byte[] target, int offset, byte[] value) {
        System.arraycopy(value, 0, target, offset, value.length);
    }
}
