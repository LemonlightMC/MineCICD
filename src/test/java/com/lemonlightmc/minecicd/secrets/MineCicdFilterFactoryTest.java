package com.lemonlightmc.minecicd.secrets;

import com.lemonlightmc.minecicd.secrets.SecretManager.SecretMapping;
import org.eclipse.jgit.attributes.FilterCommand;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MineCicdFilterFactoryTest {

    private static final String FILE_A = "plugins/a/config.yml";
    private static final String FILE_B = "plugins/b/config.yml";

    private static List<SecretMapping> withSecretsAndOtherFile() {
        return List.of(
                new SecretMapping(FILE_A, "password", "secret-a"),
                new SecretMapping(FILE_B, "token", "secret-b"));
    }

    private static String placeholder(String file, String key) {
        return new SecretMapping(file, key, "").placeholder();
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String run(MineCicdFilterFactory factory, String input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FilterCommand command = factory.create(null, new ByteArrayInputStream(b(input)), out);
        assertNotNull(command);
        assertEquals(-1, command.run());
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void scopedFactoryOnlyKeepsThatFilesEntries() throws Exception {
        MineCicdFilterFactory factoryA = new MineCicdFilterFactory(MineCicdFilterFactoryTest::withSecretsAndOtherFile, FILE_A,
                MineCicdFilterCommand.Direction.CLEAN);
        String cleaned = run(factoryA, "password: secret-a\ntoken: secret-b\n");
        assertTrue(cleaned.contains(placeholder(FILE_A, "password")));
        assertTrue(!cleaned.contains("secret-a"));
        assertTrue(cleaned.contains("secret-b"), "secrets of other files must stay out of the scoped mapping");
        assertTrue(!cleaned.contains(placeholder(FILE_B, "token")));
    }

    @Test
    void scopedFactoryWithNoEntriesYieldsIdentity() throws Exception {
        MineCicdFilterFactory factory = new MineCicdFilterFactory(MineCicdFilterFactoryTest::withSecretsAndOtherFile,
                "plugins/c/config.yml", MineCicdFilterCommand.Direction.CLEAN);
        String content = "password: secret-a\n";
        assertEquals(content, run(factory, content));
    }

    @Test
    void scopingNormalizesSeparators() throws Exception {
        List<SecretMapping> backslashFile = List.of(
                new SecretMapping("plugins\\a\\config.yml", "password", "pw"));
        MineCicdFilterFactory factory = new MineCicdFilterFactory(() -> backslashFile, FILE_A,
                MineCicdFilterCommand.Direction.CLEAN);
        String cleaned = run(factory, "password: pw\n");
        assertTrue(cleaned.contains(placeholder(FILE_A, "password")));
    }

    @Test
    void createNeverThrowsEvenWithNullSupplier() throws Exception {
        MineCicdFilterFactory factory = new MineCicdFilterFactory(null, FILE_A,
                MineCicdFilterCommand.Direction.CLEAN);
        FilterCommand command = factory.create(null, new ByteArrayInputStream(b("x")), new ByteArrayOutputStream());
        assertNotNull(command);
        assertEquals(-1, command.run());
    }

    @Test
    void createNeverThrowsWhenMappingSupplierFails() throws Exception {
        MineCicdFilterFactory factory = new MineCicdFilterFactory(() -> {
            throw new IllegalStateException("mid-reload");
        }, FILE_A, MineCicdFilterCommand.Direction.CLEAN);
        FilterCommand command = factory.create(null, new ByteArrayInputStream(b("x")), new ByteArrayOutputStream());
        assertNotNull(command);
        assertEquals(-1, command.run());
    }

    @Test
    void valueRotationOnlyAffectsTheRotatedFile() throws Exception {
        // S-07 rotation: A's secret changes from 1234 to 9999; B (no secrets)
        // contains the literal 1234 and must stay untouched even though A's old
        // value matches B's content.
        String fileBContent = "port: 1234\n";

        MineCicdFilterFactory factoryA = new MineCicdFilterFactory(
                () -> List.of(new SecretMapping(FILE_A, "password", "1234")), FILE_A,
                MineCicdFilterCommand.Direction.CLEAN);
        ByteArrayOutputStream outA = new ByteArrayOutputStream();
        FilterCommand commandA = factoryA.create(null, new ByteArrayInputStream(b("password: 1234\n")), outA);
        assertEquals(-1, commandA.run());
        assertTrue(outA.toString(StandardCharsets.UTF_8).contains(placeholder(FILE_A, "password")));

        // B's driver is registered with an empty submap -> identity.
        MineCicdFilterFactory factoryB = new MineCicdFilterFactory(() -> List.of(), FILE_B,
                MineCicdFilterCommand.Direction.CLEAN);
        assertEquals(fileBContent, run(factoryB, fileBContent));

        // Smudge path after rotation: placeholder of A's old value resolves to
        // the NEW value, never to 9999's twin in B.
        MineCicdFilterFactory rotatedA = new MineCicdFilterFactory(
                () -> List.of(new SecretMapping(FILE_A, "password", "9999")), FILE_A,
                MineCicdFilterCommand.Direction.SMUDGE);
        ByteArrayOutputStream outA2 = new ByteArrayOutputStream();
        FilterCommand rotated = rotatedA.create(null, new ByteArrayInputStream(
                b("password: " + placeholder(FILE_A, "password") + "\n")), outA2);
        assertEquals(-1, rotated.run());
        assertEquals("password: 9999\n", outA2.toString(StandardCharsets.UTF_8));
    }
}