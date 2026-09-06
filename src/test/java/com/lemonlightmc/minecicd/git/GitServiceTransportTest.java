package com.lemonlightmc.minecicd.git;

import org.junit.jupiter.api.Test;

import com.lemonlightmc.minecicd.exceptions.GitException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that GitService only accepts authenticated, encrypted remote transports
 * and rejects insecure or dangerous ones (notably git://).
 */
class GitServiceTransportTest {

    @Test
    void acceptsHttps() {
        assertDoesNotThrow(() -> GitService.validateRemoteUrl("https://github.com/bruderjulian/minecicd.git"));
        assertDoesNotThrow(() -> GitService.validateRemoteUrl("https://user:pass@git.example.com/repo.git"));
    }

    @Test
    void acceptsSshUrlScheme() {
        assertDoesNotThrow(() -> GitService.validateRemoteUrl("ssh://git@github.com/bruderjulian/minecicd.git"));
        assertDoesNotThrow(() -> GitService.validateRemoteUrl("ssh://git@git.example.com:2222/repo.git"));
    }

    @Test
    void acceptsScpStyleSsh() {
        assertDoesNotThrow(() -> GitService.validateRemoteUrl("git@github.com:bruderjulian/minecicd.git"));
        assertDoesNotThrow(() -> GitService.validateRemoteUrl("git@git.example.com:repo.git"));
    }

    @Test
    void rejectsGitProtocol() {
        assertThrows(GitException.class,
                () -> GitService.validateRemoteUrl("git://github.com/bruderjulian/minecicd.git"));
        assertThrows(GitException.class,
                () -> GitService.validateRemoteUrl("GIT://github.com/bruderjulian/minecicd.git"));
    }

    @Test
    void rejectsFileTransport() {
        assertThrows(GitException.class,
                () -> GitService.validateRemoteUrl("file:///etc/passwd"));
        assertThrows(GitException.class,
                () -> GitService.validateRemoteUrl("file:///srv/repo"));
    }

    @Test
    void rejectsExtTransport() {
        assertThrows(GitException.class,
                () -> GitService.validateRemoteUrl("ext::git-remote-fake foo bar"));
    }

    @Test
    void rejectsPathTraversalSequences() {
        assertThrows(GitException.class,
                () -> GitService.validateRemoteUrl("https://github.com/../../evil"));
        assertThrows(GitException.class,
                () -> GitService.validateRemoteUrl("ssh://git@host/.."));
    }

    @Test
    void rejectsOtherSchemesAndMalformed() {
        assertThrows(GitException.class, () -> GitService.validateRemoteUrl("http://example.com/repo.git"));
        assertThrows(GitException.class, () -> GitService.validateRemoteUrl("ftp://example.com/repo.git"));
        assertThrows(GitException.class, () -> GitService.validateRemoteUrl("git+ssh://host/repo.git"));
        assertThrows(GitException.class, () -> GitService.validateRemoteUrl(""));
        assertThrows(GitException.class, () -> GitService.validateRemoteUrl(null));
        assertThrows(GitException.class, () -> GitService.validateRemoteUrl("   "));
    }
}
