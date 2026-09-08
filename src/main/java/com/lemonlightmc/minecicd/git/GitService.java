package com.lemonlightmc.minecicd.git;

import com.lemonlightmc.minecicd.MineCICD;
import com.lemonlightmc.minecicd.exceptions.GitException;
import com.lemonlightmc.minecicd.git.Results.LogEntry;
import com.lemonlightmc.minecicd.git.Results.LogPage;
import com.lemonlightmc.minecicd.git.Results.PullResult;
import com.lemonlightmc.minecicd.git.Results.PushResult;
import com.lemonlightmc.minecicd.git.Results.StatusInfo;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitService {

    private static final int PAGE_SIZE = 5;
    private static final Pattern COMMIT_FROM_URL = Pattern.compile("[0-9a-fA-F]{40}");

    private final MineCICD plugin;
    private Git git;
    private Repository repo;

    public GitService(final MineCICD plugin) {
        this.plugin = plugin;
    }

    public boolean isInitialized() {
        if (Files.exists(plugin.serverRoot().resolve(".git"))) {
            return true;
        }
        // In a monorepo, .git may live in a parent directory. Search upward.
        Path current = plugin.serverRoot().getParent();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /**
     * Initializes the local repository (without any network activity).
     * Creates the repository with the configured initial branch and, when a
     * remote is configured, pins the {@code origin} remote locally. Does not
     * fetch, pull, push, or create commits.
     *
     * @return {@code true} if the repository was freshly created, {@code false}
     *         if it was already initialized
     * @throws GitException if the server root is part of a monorepo checkout
     *                      that is not yet a repository, or the configured
     *                      remote URL is invalid
     */
    public synchronized boolean init() {
        if (isInitialized()) {
            return false;
        }
        if (!plugin.remoteRoot().isEmpty()) {
            throw new GitException(
                    "No Git repository found. When using git.remote-server-root, "
                            + "the server root on the host must sit at that path inside an existing "
                            + "repository checkout; initialise the parent repository instead.");
        }
        openOrInit();
        final String url = plugin.config().git().repo();
        if (url != null && !url.isBlank()) {
            ensureRemote();
        }
        return true;
    }

    public synchronized PullResult pull(final boolean force) {
        final boolean alreadyInitialized = isInitialized();
        openOrInit();
        ensureRemote();
        final ObjectId oldTip = remoteBranchTip();
        fetch();
        final ObjectId newTip = remoteBranchTip();
        final List<RevCommit> commits = commitsInRange(oldTip, newTip);
        syncToConfiguredBranch(force);
        return new PullResult(commits, !alreadyInitialized);
    }

    /**
     * Removes the local repository metadata ({@code .git}) without touching any
     * working-tree files or the remote. Refuses to act when the repository is
     * not initialized, and refuses when {@code .git} lives outside the server
     * root (a monorepo checkout managed by a parent repository).
     *
     * @return {@code true} if the repository was removed, {@code false} if it
     *         was not initialized
     * @throws GitException if the encountered repository cannot be safely
     *                      removed from the server root
     */
    public synchronized boolean deinit() {
        if (!isInitialized()) {
            return false;
        }
        try {
            open();
        } catch (final IOException e) {
            throw new GitException("Unable to open repository: " + rootMessage(e), e);
        }
        final Path gitDir = repo.getDirectory().toPath().toAbsolutePath().normalize();
        final Path root = plugin.serverRoot().toAbsolutePath().normalize();
        if (!gitDir.startsWith(root)) {
            throw new GitException(
                    "Refusing to deinitialize: the repository's .git directory (" + gitDir
                            + ") lives outside the server root. This is a monorepo checkout managed by a "
                            + "parent repository.");
        }
        close();
        try {
            deleteRecursively(gitDir);
        } catch (final IOException e) {
            throw new GitException("Unable to remove .git: " + rootMessage(e), e);
        }
        return true;
    }

    public synchronized PushResult push(final String message) {
        openOrInit();
        ensureRemote();
        final String branch = plugin.config().git().branch();
        ensureLocalBranch(branch);
        Status status;
        try {
            git.add().addFilepattern(".").call();
            status = git.status().call();
        } catch (final GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
        if (status.isClean()) {
            return new PushResult(0, false);
        }
        commitWithIdentity(message);
        pushToRemote(branch);
        return new PushResult(1, true);
    }

    public synchronized int addToTracking(final String pathSpec) {
        final String entry = normalizeTrackingEntry(pathSpec);
        final GitIgnoreEditor editor = new GitIgnoreEditor(plugin.serverRoot());
        final boolean changed = editor.remove(entry);
        if (changed) {
            commitIgnoreChange("Added " + entry + " to Git tracking");
        }
        return changed ? 1 : 0;
    }

    public synchronized int removeFromTracking(final String pathSpec) {
        final String entry = normalizeTrackingEntry(pathSpec);
        final GitIgnoreEditor editor = new GitIgnoreEditor(plugin.serverRoot());
        final boolean changed = editor.add(entry);
        if (changed) {
            commitIgnoreChange("Removed " + entry + " from Git tracking");
        }
        return changed ? 1 : 0;
    }

    public synchronized void reset(final String commitRef) {
        openOrInit();
        final ObjectId id = resolveRev(commitRef);
        if (id == null) {
            throw new GitException("Invalid commit hash / link");
        }
        try {
            git.reset().setMode(ResetType.HARD).setRef(id.name()).call();
        } catch (final GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void revert(final String commitRef) {
        openOrInit();
        final ObjectId id = resolveRev(commitRef);
        if (id == null) {
            throw new GitException("Invalid commit hash / link");
        }
        try {
            git.revert().include(id).call();
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void rollback(final String dateString) {
        openOrInit();
        long target;
        try {
            target = LocalDateTime.parse(dateString, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (final Exception e) {
            throw new GitException("Invalid date format");
        }
        try (RevWalk walk = new RevWalk(repo)) {
            final ObjectId headId = repo.resolve(Constants.HEAD);
            if (headId == null) {
                throw new GitException("Repository has no commits");
            }
            final RevCommit head = walk.parseCommit(headId);
            if (target >= head.getCommitTime()) {
                throw new GitException("Date is in the future");
            }
            walk.markStart(head);
            RevCommit found = null;
            for (final RevCommit commit : walk) {
                if (commit.getCommitTime() <= target) {
                    found = commit;
                    break;
                }
            }
            if (found == null) {
                throw new GitException("No commit before the given date");
            }
            git.reset().setMode(ResetType.HARD).setRef(found.getId().name()).call();
        } catch (final GitException e) {
            throw e;
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized LogPage log(final int page) {
        openOrInit();
        try (RevWalk walk = new RevWalk(repo)) {
            final ObjectId headId = repo.resolve(Constants.HEAD);
            if (headId == null) {
                throw new GitException("Repository has no commits");
            }
            walk.markStart(walk.parseCommit(headId));
            final List<LogEntry> all = new ArrayList<>();
            for (final RevCommit commit : walk) {
                all.add(toEntry(commit, false));
            }
            final int maxPage = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page < 1 || page > maxPage) {
                return new LogPage(page, maxPage, List.of());
            }
            final int from = (page - 1) * PAGE_SIZE;
            return new LogPage(page, maxPage,
                    new ArrayList<>(all.subList(from, Math.min(from + PAGE_SIZE, all.size()))));
        } catch (final IOException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized LogEntry getCommit(final String commitRef) {
        openOrInit();
        final ObjectId id = resolveRev(commitRef);
        if (id == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            final RevCommit commit = walk.parseCommit(id);
            return toEntry(commit, true);
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized StatusInfo status() {
        if (!isInitialized()) {
            return new StatusInfo("not initialized", plugin.config().git().repo(), 0, 0);
        }
        try {
            open();
            final String branch = currentBranch();
            final Status st = git.status().call();
            final int localChanges = st.getUncommittedChanges().size();
            final int remoteChanges = behindCount(plugin.config().git().branch());
            return new StatusInfo(branch, plugin.config().git().repo(), localChanges, remoteChanges);
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized List<String> diffLocal() {
        if (!isInitialized()) {
            return List.of();
        }
        try {
            open();
            final Status st = git.status().call();
            return new ArrayList<>(new TreeSet<>(st.getUncommittedChanges()));
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized List<String> diffRemote() {
        if (!isInitialized()) {
            return List.of();
        }
        try {
            open();
            final String branch = plugin.config().git().branch();
            final ObjectId head = repo.resolve(Constants.HEAD);
            final ObjectId remote = repo.resolve(remoteBranchName(branch));
            if (head == null || remote == null) {
                return List.of();
            }
            final CanonicalTreeParser oldTree = treeParser(remote);
            final CanonicalTreeParser newTree = treeParser(head);
            final List<DiffEntry> entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
            return entries.stream().map(this::formatDiffEntry).toList();
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void resolveMergeAbort() {
        openOrInit();
        try {
            git.reset().setMode(ResetType.MERGE).call();
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void resolveRepoReset() {
        openOrInit();
        final String branch = plugin.config().git().branch();
        ObjectId remote;
        try {
            remote = repo.resolve(remoteBranchName(branch));
        } catch (final IOException e) {
            throw new GitException(rootMessage(e), e);
        }
        if (remote == null) {
            throw new GitException("Remote branch " + branch + " not found");
        }
        try {
            git.reset().setMode(ResetType.HARD).setRef(remote.name()).call();
        } catch (final GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void resolveResetLocalChanges() {
        openOrInit();
        try {
            git.reset().setMode(ResetType.HARD).setRef(Constants.HEAD).call();
        } catch (final GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void close() {
        if (git != null) {
            git.close();
            git = null;
        }
        if (repo != null) {
            repo.close();
            repo = null;
        }
    }

    private void open() throws IOException {
        if (repo != null) {
            return;
        }
        final Repository opened = new FileRepositoryBuilder()
                .setWorkTree(plugin.serverRoot().toFile())
                .findGitDir(plugin.serverRoot().toFile())
                .build();
        if (opened.getDirectory() == null) {
            throw new IOException("No .git directory found (searched up from " + plugin.serverRoot() + ")");
        }
        repo = opened;
        git = new Git(opened);
    }

    private void openOrInit() {
        try {
            if (isInitialized()) {
                open();
            } else if (!plugin.remoteRoot().isEmpty()) {
                // A remote root path means the server folder is part of an existing
                // repository checkout — never auto-initialise in that context.
                throw new GitException(
                        "No Git repository found. When using git.remote-server-root, "
                                + "the server root on the host must sit at that path inside an existing "
                                + "repository checkout; initialise the repository first.");
            } else {
                git = Git.init().setDirectory(plugin.serverRoot().toFile())
                        .setInitialBranch(plugin.config().git().branch())
                        .call();
                repo = git.getRepository();
            }
        } catch (final GitException e) {
            throw e;
        } catch (final Exception e) {
            throw new GitException("Unable to open/initialize repository: " + rootMessage(e), e);
        }
    }

    private void ensureRemote() {
        final String url = plugin.config().git().repo();
        // L-06/S-06: enforce an allowlist of authenticated, encrypted transports.
        // git:// has no transport authentication or encryption; a network attacker
        // could impersonate the remote and alter files during a pull.
        validateRemoteUrl(url);
        try {
            final boolean hasOrigin = repo.getConfig().getSubsections("remote").contains("origin");
            if (hasOrigin) {
                git.remoteSetUrl().setRemoteName("origin").setRemoteUri(new URIish(url)).call();
            } else {
                git.remoteAdd().setName("origin").setUri(new URIish(url)).call();
            }
        } catch (final Exception e) {
            throw new GitException("Unable to configure remote: " + rootMessage(e), e);
        }
    }

    /**
     * Validates that a configured remote URL uses an authenticated, encrypted
     * transport.
     * Allowed schemes:
     * <ul>
     * <li>{@code https://} - HTTPS transport with server certificate
     * verification.</li>
     * <li>{@code ssh://[user@]host/path} - SSH transport.</li>
     * <li>{@code git@host:path} - scp-style SSH URL.</li>
     * </ul>
     * Explicitly rejected: {@code git://} (unauthenticated, unencrypted),
     * {@code file://},
     * {@code ext::}, and any URL containing {@code ..} (path traversal / transport
     * smuggling).
     *
     * @throws GitException if the URL is null, blank, or uses a disallowed
     *                      transport
     */
    static void validateRemoteUrl(final String url) {
        if (url == null || url.isBlank()) {
            throw new GitException("No remote repository configured (git.repo)");
        }
        final String lower = url.toLowerCase();
        if (lower.startsWith("file://") || lower.startsWith("ext::") || lower.contains("..")
                || lower.startsWith("git://")) {
            throw new GitException("Remote URL not allowed: " + url);
        }
        if (!(lower.startsWith("https://") || lower.startsWith("ssh://") || lower.startsWith("git@"))) {
            throw new GitException("Remote URL must be https://, ssh:// or git@, got: " + url);
        }
    }

    private void fetch() {
        try {
            git.fetch().setRemote("origin").setCredentialsProvider(credentials()).call();
        } catch (final Exception e) {
            throw new GitException("Fetch failed: " + rootMessage(e), e);
        }
    }

    private CredentialsProvider credentials() {
        final String user = plugin.config().git().user();
        if (user == null || user.isEmpty()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(user, plugin.config().git().pass());
    }

    private ObjectId remoteBranchTip() {
        try {
            final Ref ref = repo.exactRef(remoteBranchName(plugin.config().git().branch()));
            return ref == null ? null : ref.getObjectId();
        } catch (final IOException e) {
            return null;
        }
    }

    private String remoteBranchName(final String branch) {
        return Constants.R_REMOTES + "origin/" + branch;
    }

    private List<RevCommit> commitsInRange(final ObjectId oldTip, final ObjectId newTip) {
        if (newTip == null) {
            return List.of();
        }
        try (RevWalk walk = new RevWalk(repo)) {
            final RevCommit newCommit = walk.parseCommit(newTip);
            if (oldTip == null) {
                walk.markStart(newCommit);
                final List<RevCommit> out = new ArrayList<>();
                for (final RevCommit commit : walk) {
                    if (out.size() >= 100) {
                        break;
                    }
                    out.add(commit);
                }
                return out;
            }
            final RevCommit oldCommit = walk.parseCommit(oldTip);
            if (oldCommit.getId().equals(newCommit.getId())) {
                return List.of();
            }
            walk.markStart(newCommit);
            walk.markUninteresting(oldCommit);
            final List<RevCommit> out = new ArrayList<>();
            for (final RevCommit commit : walk) {
                if (out.size() >= 100) {
                    break;
                }
                out.add(commit);
            }
            return out;
        } catch (final IOException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private void syncToConfiguredBranch(final boolean force) {
        final String branch = plugin.config().git().branch();
        final Ref remoteRef = findOrNull(remoteBranchName(branch));
        final Ref localRef = findOrNull(Constants.R_HEADS + branch);
        if (remoteRef == null || remoteRef.getObjectId() == null) {
            if (localRef != null && localRef.getObjectId() != null) {
                throw new GitException("Remote branch " + branch + " not found on the remote");
            }
            if (!branch.equals(currentBranch())) {
                createOrCheckout(branch, null);
            }
            return;
        }
        if (localRef == null || localRef.getObjectId() == null) {
            createOrCheckout(branch, remoteRef.getName());
            return;
        }
        if (!branch.equals(currentBranch())) {
            checkout(branch);
        }
        final int ahead = countAhead(localRef.getObjectId(), remoteRef.getObjectId());
        if (ahead > 0 && !force) {
            throw new GitException.PullAborted("unpushed changes");
        }
        try {
            git.reset().setMode(ResetType.HARD).setRef(remoteRef.getName()).call();
        } catch (final GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private void ensureLocalBranch(final String branch) {
        final Ref localRef = findOrNull(Constants.R_HEADS + branch);
        try {
            if (localRef == null || localRef.getObjectId() == null) {
                createOrCheckout(branch, null);
            } else if (!branch.equals(currentBranch())) {
                checkout(branch);
            }
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private void createOrCheckout(final String branch, final String startPoint) {
        try {
            if (startPoint == null) {
                git.checkout().setName(branch).setCreateBranch(true).call();
            } else {
                git.checkout().setName(branch).setCreateBranch(true).setStartPoint(startPoint).call();
            }
        } catch (final GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private void checkout(final String branch) {
        try {
            git.checkout().setName(branch).call();
        } catch (final GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private int countAhead(final ObjectId local, final ObjectId remote) {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(local));
            walk.markUninteresting(walk.parseCommit(remote));
            int count = 0;
            for (final RevCommit ignored : walk) {
                if (++count > 1000) {
                    break;
                }
            }
            return count;
        } catch (final IOException e) {
            return 0;
        }
    }

    private int behindCount(final String branch) {
        final Ref remoteRef = findOrNull(remoteBranchName(branch));
        final Ref localHead = findOrNull(Constants.HEAD);
        if (remoteRef == null || localHead == null || remoteRef.getObjectId() == null
                || localHead.getObjectId() == null) {
            return 0;
        }
        return countAhead(remoteRef.getObjectId(), localHead.getObjectId());
    }

    private Ref findOrNull(final String name) {
        try {
            return repo.findRef(name);
        } catch (final IOException e) {
            return null;
        }
    }

    private String currentBranch() {
        try {
            return repo.getBranch();
        } catch (final Exception e) {
            return Constants.HEAD;
        }
    }

    private void commitWithIdentity(final String message) {
        String name = plugin.config().git().user();
        if (name == null || name.isBlank()) {
            name = "MineCICD";
        }
        String email = plugin.config().git().email();
        if (email == null || email.isBlank()) {
            email = "minecicd@minecicd.local";
        }
        final PersonIdent identity = new PersonIdent(name, email);
        try {
            git.commit().setMessage(message).setAuthor(identity).setCommitter(identity).call();
        } catch (final GitAPIException e) {
            throw new GitException("Commit failed: " + rootMessage(e), e);
        }
    }

    private void commitIgnoreChange(final String message) {
        openOrInit();
        ensureRemote();
        ensureLocalBranch(plugin.config().git().branch());
        try {
            git.add().addFilepattern(".gitignore").call();
            commitWithIdentity(message);
        } catch (final Exception e) {
            throw new GitException(rootMessage(e), e);
        }
        pushToRemote(plugin.config().git().branch());
    }

    private void pushToRemote(final String branch) {
        try {
            final Iterable<org.eclipse.jgit.transport.PushResult> results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                    .setCredentialsProvider(credentials())
                    .call();
            for (final org.eclipse.jgit.transport.PushResult result : results) {
                for (final RemoteRefUpdate update : result.getRemoteUpdates()) {
                    final RemoteRefUpdate.Status status = update.getStatus();
                    if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        final String message = update.getMessage() == null ? status.name() : update.getMessage();
                        throw new GitException("Push rejected: " + message);
                    }
                }
            }
        } catch (final GitException e) {
            throw e;
        } catch (final GitAPIException e) {
            throw new GitException("Push failed: " + rootMessage(e), e);
        }
    }

    private ObjectId resolveRev(final String input) {
        if (input == null) {
            return null;
        }
        try {
            final Matcher matcher = COMMIT_FROM_URL.matcher(input);
            if (matcher.find()) {
                return repo.resolve(matcher.group());
            }
            return repo.resolve(input.trim());
        } catch (final Exception e) {
            return null;
        }
    }

    private LogEntry toEntry(final RevCommit commit, final boolean withChanges) {
        final String date = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss")
                .format(new Date(commit.getCommitTime() * 1000L));
        final String message = joinMessage(commit.getFullMessage());
        final List<String> changes = withChanges ? changesFor(commit) : List.of();
        return new LogEntry(commit.getId().name(), commit.getAuthorIdent().getName(), date, message, changes);
    }

    private String joinMessage(final String raw) {
        if (raw == null) {
            return "";
        }
        final String single = raw.replace("\r", "").replace("\n", " ").trim();
        return single;
    }

    private List<String> changesFor(final RevCommit commit) {
        try (RevWalk walk = new RevWalk(repo)) {
            final RevCommit parent = commit.getParentCount() > 0 ? walk.parseCommit(commit.getParent(0).getId()) : null;
            if (parent == null) {
                return treeNames(commit.getTree());
            }
            final List<DiffEntry> entries = git.diff()
                    .setOldTree(treeParser(parent))
                    .setNewTree(treeParser(commit))
                    .call();
            final List<String> out = new ArrayList<>();
            for (final DiffEntry entry : entries) {
                out.add(formatDiffEntry(entry));
            }
            return out;
        } catch (final Exception e) {
            return List.of();
        }
    }

    private List<String> treeNames(final RevTree tree) throws IOException {
        final List<String> names = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(true);
            while (tw.next()) {
                names.add(tw.getPathString());
            }
        }
        return names;
    }

    private CanonicalTreeParser treeParser(final ObjectId commitId) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            return treeParser(walk.parseCommit(commitId));
        }
    }

    private CanonicalTreeParser treeParser(final RevCommit commit) throws IOException {
        final RevTree tree = commit.getTree();
        final CanonicalTreeParser parser = new CanonicalTreeParser();
        try (org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader()) {
            parser.reset(reader, tree.getId());
        }
        return parser;
    }

    private String formatDiffEntry(final DiffEntry entry) {
        final String oldPath = entry.getOldPath();
        final String newPath = entry.getNewPath();
        if (!oldPath.equals(newPath)) {
            return oldPath + " -> " + newPath;
        }
        if ("/dev/null".equals(oldPath)) {
            return newPath;
        }
        return oldPath;
    }

    private String normalizeTrackingEntry(final String pathSpec) {
        String p = pathSpec == null ? "" : pathSpec.trim().replace('\\', '/');
        if (p.isEmpty()) {
            throw new GitException("Empty path");
        }
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        for (final String segment : p.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new GitException("Invalid path: " + pathSpec);
            }
        }
        // M-05: rely solely on syntactic trailing '/' (TOCTOU-safe); callers must
        // include '/' for directories
        final Path resolved = plugin.serverRoot().resolve(p).normalize().toAbsolutePath();
        final Path base = plugin.serverRoot().normalize().toAbsolutePath();
        if (!resolved.startsWith(base)) {
            throw new GitException("Path escapes server root: " + pathSpec);
        }
        return p;
    }

    private String rootMessage(final Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static void deleteRecursively(final Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(dir)) {
            for (final Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}