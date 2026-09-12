package com.codearena.worker.execution;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Writes a one-file tar stream, which is the format {@code docker cp -} expects.
 *
 * <p>Hand-rolled rather than pulled in as a dependency: the need is a single regular file
 * with a fixed, server-chosen name, and a full archive library would be a large surface
 * added for thirty lines of format. The header layout is the POSIX ustar one.
 *
 * <p>The file name never comes from user input — {@link LanguageSpec} fixes it per
 * language — so there is no path traversal to guard against here. The guard is still
 * asserted below, because a tar entry name is exactly the kind of thing that becomes
 * attacker-controlled two refactors later.
 */
final class TarWriter {

    private static final int BLOCK_SIZE = 512;
    private static final int NAME_LENGTH = 100;

    private TarWriter() {
    }

    static void writeSingleFile(OutputStream out, String fileName, byte[] content) throws IOException {
        if (fileName.contains("/") || fileName.contains("..") || fileName.length() >= NAME_LENGTH) {
            throw new IllegalArgumentException("Unsafe tar entry name");
        }

        out.write(header(fileName, content.length));
        out.write(content);

        // Entries are padded to a block boundary.
        int padding = (BLOCK_SIZE - (content.length % BLOCK_SIZE)) % BLOCK_SIZE;
        out.write(new byte[padding]);

        // Two empty blocks mark the end of the archive.
        out.write(new byte[BLOCK_SIZE * 2]);
        out.flush();
    }

    private static byte[] header(String fileName, int size) {
        byte[] header = new byte[BLOCK_SIZE];

        writeString(header, 0, fileName, NAME_LENGTH);
        writeString(header, 100, "0000644", 8);       // mode: rw-r--r--
        writeString(header, 108, "0000000", 8);       // uid 0
        writeString(header, 116, "0000000", 8);       // gid 0
        writeString(header, 124, String.format("%011o", size), 12);
        writeString(header, 136, String.format("%011o", System.currentTimeMillis() / 1000), 12);
        writeString(header, 156, "0", 1);             // type: regular file
        writeString(header, 257, "ustar", 6);
        writeString(header, 263, "00", 2);

        // The checksum is computed with its own field treated as spaces, then written back.
        Arrays.fill(header, 148, 156, (byte) ' ');
        int checksum = 0;
        for (byte b : header) {
            checksum += b & 0xFF;
        }
        writeString(header, 148, String.format("%06o", checksum), 7);
        header[154] = 0;
        header[155] = ' ';

        return header;
    }

    private static void writeString(byte[] target, int offset, String value, int maxLength) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int length = Math.min(bytes.length, maxLength);
        System.arraycopy(bytes, 0, target, offset, length);
    }
}
