package com.lemonlightmc.minecicd.secrets;

import com.lemonlightmc.minecicd.secrets.SecretManager.SecretMapping;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineCicdFilterCommandTest {

    private static final String FILE = "plugins/example/config.yml";
    private static final String FILE_A = "plugins/a/config.yml";
    private static final String FILE_B = "plugins/b/config.yml";

    private static List<SecretMapping> mapping() {
        return List.of(
                new SecretMapping(FILE, "database_password", "s3cr3t!"),
                new SecretMapping(FILE, "token", "abc:def/ghi"));
    }

    private static String placeholder(String file, String key) {
        return new SecretMapping(file, key, "").placeholder();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String run(MineCicdFilterCommand.Direction direction, String input,
            List<SecretMapping> mapping, String file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MineCicdFilterCommand command = new MineCicdFilterCommand(direction, file, mapping,
                new ByteArrayInputStream(b(input)), out);
        assertEquals(-1, command.run(), "run() must return -1 when finished");
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void cleanReplacesValuesWithPlaceholders() throws Exception {
        String cleaned = run(MineCicdFilterCommand.Direction.CLEAN,
                "password: s3cr3t!\ntoken: abc:def/ghi\n", mapping(), FILE);
        assertTrue(cleaned.contains(placeholder(FILE, "database_password")));
        assertTrue(cleaned.contains(placeholder(FILE, "token")));
        assertTrue(!cleaned.contains("s3cr3t!") && !cleaned.contains("abc:def/ghi"));
    }

    @Test
    void smudgeRestoresValues() throws Exception {
        String input = "password: " + placeholder(FILE, "database_password")
                + "\ntoken: " + placeholder(FILE, "token") + "\n";
        String smudged = run(MineCicdFilterCommand.Direction.SMUDGE, input, mapping(), FILE);
        assertTrue(smudged.contains("s3cr3t!"));
        assertTrue(smudged.contains("abc:def/ghi"));
        assertTrue(!smudged.contains("__MCICD_"));
    }

    @Test
    void cleanThenSmudgeIsIdentity() throws Exception {
        String original = "password: s3cr3t!\ntoken: abc:def/ghi\nother: value\n";
        String cleaned = run(MineCicdFilterCommand.Direction.CLEAN, original, mapping(), FILE);
        String roundTrip = run(MineCicdFilterCommand.Direction.SMUDGE, cleaned, mapping(), FILE);
        assertEquals(original, roundTrip);
    }

    @Test
    void handlesUtf8Values() throws Exception {
        List<SecretMapping> unicode = List.of(
                new SecretMapping(FILE, "pass", "p\u00e4ssw\u00f6rd\u2713\ud83d\udd10"));
        String original = "pass: p\u00e4ssw\u00f6rd\u2713\ud83d\udd10\n";
        String cleaned = run(MineCicdFilterCommand.Direction.CLEAN, original, unicode, FILE);
        assertTrue(cleaned.contains(placeholder(FILE, "pass")));
        assertEquals(original, run(MineCicdFilterCommand.Direction.SMUDGE, cleaned, unicode, FILE));
    }

    @Test
    void emptyMappingIsIdentity() throws Exception {
        String content = "password: s3cr3t!\n";
        assertEquals(content, run(MineCicdFilterCommand.Direction.CLEAN, content, List.of(), FILE));
        assertEquals(content, run(MineCicdFilterCommand.Direction.SMUDGE, content, List.of(), FILE));
    }

    @Test
    void secondRunReturnsMinusOneWithoutRewriting() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MineCicdFilterCommand command = new MineCicdFilterCommand(MineCicdFilterCommand.Direction.CLEAN, FILE,
                mapping(), new ByteArrayInputStream(b("password: s3cr3t!\n")), out);
        assertEquals(-1, command.run());
        assertEquals(-1, command.run(), "second run() must not re-read input");
        assertTrue(!out.toString(StandardCharsets.UTF_8).contains("s3cr3t!"));
    }

    private static final class CloseRecordingInputStream extends FilterInputStream {
        boolean closed;

        CloseRecordingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class CloseRecordingOutputStream extends FilterOutputStream {
        boolean closed;

        CloseRecordingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    @Test
    void closesStreamsOnSuccess() throws Exception {
        CloseRecordingInputStream in = new CloseRecordingInputStream(
                new ByteArrayInputStream(b("password: s3cr3t!\n")));
        CloseRecordingOutputStream out = new CloseRecordingOutputStream(new ByteArrayOutputStream());
        MineCicdFilterCommand command = new MineCicdFilterCommand(MineCicdFilterCommand.Direction.CLEAN, FILE,
                mapping(), in, out);
        assertEquals(-1, command.run());
        assertTrue(in.closed, "input stream must be closed after run()");
        assertTrue(out.closed, "output stream must be closed after run()");
    }

    @Test
    void propagatesIoExceptionAndClosesStreams() throws Exception {
        // A JGit clean/smudge process communicates errors by reading EOF from the
        // child; an exception in run() must propagate to abort the git operation.
        InputStream throwing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom");
            }
        };
        CloseRecordingInputStream in = new CloseRecordingInputStream(throwing);
        CloseRecordingOutputStream out = new CloseRecordingOutputStream(new ByteArrayOutputStream());
        MineCicdFilterCommand command = new MineCicdFilterCommand(MineCicdFilterCommand.Direction.CLEAN, FILE,
                mapping(), in, out);
        assertThrows(IOException.class, command::run);
        assertTrue(in.closed, "input stream must be closed after a failing run()");
        assertTrue(out.closed, "output stream must be closed after a failing run()");
    }

    @Test
    void placeholderIsStableAndUniquePerKey() {
        String a = placeholder(FILE, "keyA");
        String b = placeholder(FILE, "keyA");
        assertEquals(a, b);
        assertFalse(a.contains("keyA"), "placeholder must not leak the raw key");
    }

    @Test
    void cleanOnlySubstitutesSecretsOfTheTargetFile() throws Exception {
        List<SecretMapping> twoFiles = List.of(
                new SecretMapping(FILE_A, "db_password", "secret-a"),
                new SecretMapping(FILE_B, "api_token", "secret-b"));
        String content = "db: secret-a\ntoken: secret-b\n";
        String cleaned = run(MineCicdFilterCommand.Direction.CLEAN, content, twoFiles, FILE_A);
        assertTrue(cleaned.contains(placeholder(FILE_A, "db_password")));
        assertFalse(cleaned.contains("secret-a"));
        assertTrue(cleaned.contains("secret-b"), "another file's secret must not be touched");
        assertFalse(cleaned.contains(placeholder(FILE_B, "api_token")));
    }

    @Test
    void smudgeOnlyRestoresSecretsOfTheTargetFile() throws Exception {
        List<SecretMapping> twoFiles = List.of(
                new SecretMapping(FILE_A, "db_password", "secret-a"),
                new SecretMapping(FILE_B, "api_token", "secret-b"));
        String input = "db: " + placeholder(FILE_A, "db_password")
                + "\ntoken: " + placeholder(FILE_B, "api_token") + "\n";
        String smudged = run(MineCicdFilterCommand.Direction.SMUDGE, input, twoFiles, FILE_A);
        assertTrue(smudged.contains("secret-a"));
        assertFalse(smudged.contains("secret-b"), "another file's secret must not leak into this one");
        assertTrue(smudged.contains(placeholder(FILE_B, "api_token")),
                "foreign placeholder must remain unresolved");
    }

    @Test
    void cleanIsIdentityWhenTargetFileHasNoEntries() throws Exception {
        List<SecretMapping> otherFileOnly = List.of(new SecretMapping(FILE_B, "password", "pw"));
        String content = "password: pw\n";
        assertEquals(content, run(MineCicdFilterCommand.Direction.CLEAN, content, otherFileOnly, FILE_A));
    }

    @Test
    void targetFileUsesForwardSlashesRegardlessOfInputSeparator() throws Exception {
        List<SecretMapping> map = List.of(new SecretMapping(FILE_A, "password", "pw"));
        String cleaned = run(MineCicdFilterCommand.Direction.CLEAN, "password: pw\n", map,
                "plugins\\a\\config.yml");
        assertTrue(cleaned.contains(placeholder(FILE_A, "password")));
    }
}