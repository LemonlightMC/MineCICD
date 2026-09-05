package com.lemonlightmc.minecicd.git;

import com.lemonlightmc.minecicd.MineCICD;
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

    public GitService(MineCICD plugin) {
        this.plugin = plugin;
    }

    public boolean isInitialized() {
        return Files.exists(plugin.serverRoot().resolve(".git"));
    }

    public synchronized PullResult pull(boolean force) {
        boolean alreadyInitialized = isInitialized();
        openOrInit();
        ensureRemote();
        ObjectId oldTip = remoteBranchTip();
        fetch();
        ObjectId newTip = remoteBranchTip();
        List<RevCommit> commits = commitsInRange(oldTip, newTip);
        syncToConfiguredBranch(force);
        return new PullResult(commits, !alreadyInitialized);
    }

    public synchronized PushResult push(String message) {
        openOrInit();
        ensureRemote();
        String branch = plugin.config().git().branch();
        ensureLocalBranch(branch);
        Status status;
        try {
            git.add().addFilepattern(".").call();
            status = git.status().call();
        } catch (GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
        if (status.isClean()) {
            return new PushResult(0, false);
        }
        commitWithIdentity(message);
        pushToRemote(branch);
        return new PushResult(1, true);
    }

    public synchronized int addToTracking(String pathSpec) {
        String entry = normalizeTrackingEntry(pathSpec);
        GitIgnoreEditor editor = new GitIgnoreEditor(plugin.serverRoot());
        boolean changed = editor.remove(entry);
        if (changed) {
            commitIgnoreChange("Added " + entry + " to Git tracking");
        }
        return changed ? 1 : 0;
    }

    public synchronized int removeFromTracking(String pathSpec) {
        String entry = normalizeTrackingEntry(pathSpec);
        GitIgnoreEditor editor = new GitIgnoreEditor(plugin.serverRoot());
        boolean changed = editor.add(entry);
        if (changed) {
            commitIgnoreChange("Removed " + entry + " from Git tracking");
        }
        return changed ? 1 : 0;
    }

    public synchronized void reset(String commitRef) {
        openOrInit();
        ObjectId id = resolveRev(commitRef);
        if (id == null) {
            throw new GitException("Invalid commit hash / link");
        }
        try {
            git.reset().setMode(ResetType.HARD).setRef(id.name()).call();
        } catch (GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void revert(String commitRef) {
        openOrInit();
        ObjectId id = resolveRev(commitRef);
        if (id == null) {
            throw new GitException("Invalid commit hash / link");
        }
        try {
            git.revert().include(id).call();
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void rollback(String dateString) {
        openOrInit();
        long target;
        try {
            target = LocalDateTime.parse(dateString, DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"))
                    .atZone(ZoneId.systemDefault()).toEpochSecond();
        } catch (Exception e) {
            throw new GitException("Invalid date format");
        }
        try (RevWalk walk = new RevWalk(repo)) {
            ObjectId headId = repo.resolve(Constants.HEAD);
            if (headId == null) {
                throw new GitException("Repository has no commits");
            }
            RevCommit head = walk.parseCommit(headId);
            if (target >= head.getCommitTime()) {
                throw new GitException("Date is in the future");
            }
            walk.markStart(head);
            RevCommit found = null;
            for (RevCommit commit : walk) {
                if (commit.getCommitTime() <= target) {
                    found = commit;
                    break;
                }
            }
            if (found == null) {
                throw new GitException("No commit before the given date");
            }
            git.reset().setMode(ResetType.HARD).setRef(found.getId().name()).call();
        } catch (GitException e) {
            throw e;
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized LogPage log(int page) {
        openOrInit();
        try (RevWalk walk = new RevWalk(repo)) {
            ObjectId headId = repo.resolve(Constants.HEAD);
            if (headId == null) {
                throw new GitException("Repository has no commits");
            }
            walk.markStart(walk.parseCommit(headId));
            List<LogEntry> all = new ArrayList<>();
            for (RevCommit commit : walk) {
                all.add(toEntry(commit, false));
            }
            int maxPage = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
            if (page < 1 || page > maxPage) {
                return new LogPage(page, maxPage, List.of());
            }
            int from = (page - 1) * PAGE_SIZE;
            return new LogPage(page, maxPage,
                    new ArrayList<>(all.subList(from, Math.min(from + PAGE_SIZE, all.size()))));
        } catch (IOException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized LogEntry getCommit(String commitRef) {
        openOrInit();
        ObjectId id = resolveRev(commitRef);
        if (id == null) {
            return null;
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit commit = walk.parseCommit(id);
            return toEntry(commit, true);
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized StatusInfo status() {
        if (!isInitialized()) {
            return new StatusInfo("not initialized", plugin.config().git().repo(), 0, 0);
        }
        try {
            open();
            String branch = currentBranch();
            Status st = git.status().call();
            int localChanges = st.getUncommittedChanges().size();
            int remoteChanges = behindCount(plugin.config().git().branch());
            return new StatusInfo(branch, plugin.config().git().repo(), localChanges, remoteChanges);
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized List<String> diffLocal() {
        if (!isInitialized()) {
            return List.of();
        }
        try {
            open();
            Status st = git.status().call();
            return new ArrayList<>(new TreeSet<>(st.getUncommittedChanges()));
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized List<String> diffRemote() {
        if (!isInitialized()) {
            return List.of();
        }
        try {
            open();
            String branch = plugin.config().git().branch();
            ObjectId head = repo.resolve(Constants.HEAD);
            ObjectId remote = repo.resolve(remoteBranchName(branch));
            if (head == null || remote == null) {
                return List.of();
            }
            CanonicalTreeParser oldTree = treeParser(remote);
            CanonicalTreeParser newTree = treeParser(head);
            List<DiffEntry> entries = git.diff().setOldTree(oldTree).setNewTree(newTree).call();
            return entries.stream().map(this::formatDiffEntry).toList();
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void resolveMergeAbort() {
        openOrInit();
        try {
            git.reset().setMode(ResetType.MERGE).call();
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void resolveRepoReset() {
        openOrInit();
        String branch = plugin.config().git().branch();
        ObjectId remote;
        try {
            remote = repo.resolve(remoteBranchName(branch));
        } catch (IOException e) {
            throw new GitException(rootMessage(e), e);
        }
        if (remote == null) {
            throw new GitException("Remote branch " + branch + " not found");
        }
        try {
            git.reset().setMode(ResetType.HARD).setRef(remote.name()).call();
        } catch (GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    public synchronized void resolveResetLocalChanges() {
        openOrInit();
        try {
            git.reset().setMode(ResetType.HARD).setRef(Constants.HEAD).call();
        } catch (GitAPIException e) {
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
        Repository opened = new FileRepositoryBuilder()
                .setWorkTree(plugin.serverRoot().toFile())
                .findGitDir(plugin.serverRoot().toFile())
                .build();
        repo = opened;
        git = new Git(opened);
    }

    private void openOrInit() {
        try {
            if (isInitialized()) {
                open();
            } else {
                git = Git.init().setDirectory(plugin.serverRoot().toFile())
                        .setInitialBranch(plugin.config().git().branch())
                        .call();
                repo = git.getRepository();
            }
        } catch (Exception e) {
            throw new GitException("Unable to open/initialize repository: " + rootMessage(e), e);
        }
    }

    private void ensureRemote() {
        String url = plugin.config().git().repo();
        // L-06/S-06: enforce an allowlist of authenticated, encrypted transports.
        // git:// has no transport authentication or encryption; a network attacker
        // could impersonate the remote and alter files during a pull.
        validateRemoteUrl(url);
        try {
            boolean hasOrigin = repo.getConfig().getSubsections("remote").contains("origin");
            if (hasOrigin) {
                git.remoteSetUrl().setRemoteName("origin").setRemoteUri(new URIish(url)).call();
            } else {
                git.remoteAdd().setName("origin").setUri(new URIish(url)).call();
            }
        } catch (Exception e) {
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
    static void validateRemoteUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new GitException("No remote repository configured (git.repo)");
        }
        String lower = url.toLowerCase();
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
        } catch (Exception e) {
            throw new GitException("Fetch failed: " + rootMessage(e), e);
        }
    }

    private CredentialsProvider credentials() {
        String user = plugin.config().git().user();
        if (user == null || user.isEmpty()) {
            return null;
        }
        return new UsernamePasswordCredentialsProvider(user, plugin.config().git().pass());
    }

    private ObjectId remoteBranchTip() {
        try {
            Ref ref = repo.exactRef(remoteBranchName(plugin.config().git().branch()));
            return ref == null ? null : ref.getObjectId();
        } catch (IOException e) {
            return null;
        }
    }

    private String remoteBranchName(String branch) {
        return Constants.R_REMOTES + "origin/" + branch;
    }

    private List<RevCommit> commitsInRange(ObjectId oldTip, ObjectId newTip) {
        if (newTip == null) {
            return List.of();
        }
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit newCommit = walk.parseCommit(newTip);
            if (oldTip == null) {
                walk.markStart(newCommit);
                List<RevCommit> out = new ArrayList<>();
                for (RevCommit commit : walk) {
                    if (out.size() >= 100) {
                        break;
                    }
                    out.add(commit);
                }
                return out;
            }
            RevCommit oldCommit = walk.parseCommit(oldTip);
            if (oldCommit.getId().equals(newCommit.getId())) {
                return List.of();
            }
            walk.markStart(newCommit);
            walk.markUninteresting(oldCommit);
            List<RevCommit> out = new ArrayList<>();
            for (RevCommit commit : walk) {
                if (out.size() >= 100) {
                    break;
                }
                out.add(commit);
            }
            return out;
        } catch (IOException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private void syncToConfiguredBranch(boolean force) {
        String branch = plugin.config().git().branch();
        Ref remoteRef = findOrNull(remoteBranchName(branch));
        Ref localRef = findOrNull(Constants.R_HEADS + branch);
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
        int ahead = countAhead(localRef.getObjectId(), remoteRef.getObjectId());
        if (ahead > 0 && !force) {
            throw new GitException.PullAborted("unpushed changes");
        }
        try {
            git.reset().setMode(ResetType.HARD).setRef(remoteRef.getName()).call();
        } catch (GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private void ensureLocalBranch(String branch) {
        Ref localRef = findOrNull(Constants.R_HEADS + branch);
        try {
            if (localRef == null || localRef.getObjectId() == null) {
                createOrCheckout(branch, null);
            } else if (!branch.equals(currentBranch())) {
                checkout(branch);
            }
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private void createOrCheckout(String branch, String startPoint) {
        try {
            if (startPoint == null) {
                git.checkout().setName(branch).setCreateBranch(true).call();
            } else {
                git.checkout().setName(branch).setCreateBranch(true).setStartPoint(startPoint).call();
            }
        } catch (GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private void checkout(String branch) {
        try {
            git.checkout().setName(branch).call();
        } catch (GitAPIException e) {
            throw new GitException(rootMessage(e), e);
        }
    }

    private int countAhead(ObjectId local, ObjectId remote) {
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(local));
            walk.markUninteresting(walk.parseCommit(remote));
            int count = 0;
            for (RevCommit ignored : walk) {
                if (++count > 1000) {
                    break;
                }
            }
            return count;
        } catch (IOException e) {
            return 0;
        }
    }

    private int behindCount(String branch) {
        Ref remoteRef = findOrNull(remoteBranchName(branch));
        Ref localHead = findOrNull(Constants.HEAD);
        if (remoteRef == null || localHead == null || remoteRef.getObjectId() == null
                || localHead.getObjectId() == null) {
            return 0;
        }
        return countAhead(remoteRef.getObjectId(), localHead.getObjectId());
    }

    private Ref findOrNull(String name) {
        try {
            return repo.findRef(name);
        } catch (IOException e) {
            return null;
        }
    }

    private String currentBranch() {
        try {
            return repo.getBranch();
        } catch (Exception e) {
            return Constants.HEAD;
        }
    }

    private void commitWithIdentity(String message) {
        String name = plugin.config().git().user();
        if (name == null || name.isBlank()) {
            name = "MineCICD";
        }
        String email = plugin.config().git().email();
        if (email == null || email.isBlank()) {
            email = "minecicd@minecicd.local";
        }
        PersonIdent identity = new PersonIdent(name, email);
        try {
            git.commit().setMessage(message).setAuthor(identity).setCommitter(identity).call();
        } catch (GitAPIException e) {
            throw new GitException("Commit failed: " + rootMessage(e), e);
        }
    }

    private void commitIgnoreChange(String message) {
        openOrInit();
        ensureRemote();
        ensureLocalBranch(plugin.config().git().branch());
        try {
            git.add().addFilepattern(".gitignore").call();
            commitWithIdentity(message);
        } catch (Exception e) {
            throw new GitException(rootMessage(e), e);
        }
        pushToRemote(plugin.config().git().branch());
    }

    private void pushToRemote(String branch) {
        try {
            Iterable<org.eclipse.jgit.transport.PushResult> results = git.push()
                    .setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch))
                    .setCredentialsProvider(credentials())
                    .call();
            for (org.eclipse.jgit.transport.PushResult result : results) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    RemoteRefUpdate.Status status = update.getStatus();
                    if (status != RemoteRefUpdate.Status.OK && status != RemoteRefUpdate.Status.UP_TO_DATE) {
                        String message = update.getMessage() == null ? status.name() : update.getMessage();
                        throw new GitException("Push rejected: " + message);
                    }
                }
            }
        } catch (GitException e) {
            throw e;
        } catch (GitAPIException e) {
            throw new GitException("Push failed: " + rootMessage(e), e);
        }
    }

    private ObjectId resolveRev(String input) {
        if (input == null) {
            return null;
        }
        try {
            Matcher matcher = COMMIT_FROM_URL.matcher(input);
            if (matcher.find()) {
                return repo.resolve(matcher.group());
            }
            return repo.resolve(input.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private LogEntry toEntry(RevCommit commit, boolean withChanges) {
        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").format(new Date(commit.getCommitTime() * 1000L));
        String message = joinMessage(commit.getFullMessage());
        List<String> changes = withChanges ? changesFor(commit) : List.of();
        return new LogEntry(commit.getId().name(), commit.getAuthorIdent().getName(), date, message, changes);
    }

    private String joinMessage(String raw) {
        if (raw == null) {
            return "";
        }
        String single = raw.replace("\r", "").replace("\n", " ").trim();
        return single;
    }

    private List<String> changesFor(RevCommit commit) {
        try (RevWalk walk = new RevWalk(repo)) {
            RevCommit parent = commit.getParentCount() > 0 ? walk.parseCommit(commit.getParent(0).getId()) : null;
            if (parent == null) {
                return treeNames(commit.getTree());
            }
            List<DiffEntry> entries = git.diff()
                    .setOldTree(treeParser(parent))
                    .setNewTree(treeParser(commit))
                    .call();
            List<String> out = new ArrayList<>();
            for (DiffEntry entry : entries) {
                out.add(formatDiffEntry(entry));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> treeNames(RevTree tree) throws IOException {
        List<String> names = new ArrayList<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(tree);
            tw.setRecursive(true);
            while (tw.next()) {
                names.add(tw.getPathString());
            }
        }
        return names;
    }

    private CanonicalTreeParser treeParser(ObjectId commitId) throws IOException {
        try (RevWalk walk = new RevWalk(repo)) {
            return treeParser(walk.parseCommit(commitId));
        }
    }

    private CanonicalTreeParser treeParser(RevCommit commit) throws IOException {
        RevTree tree = commit.getTree();
        CanonicalTreeParser parser = new CanonicalTreeParser();
        try (org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader()) {
            parser.reset(reader, tree.getId());
        }
        return parser;
    }

    private String formatDiffEntry(DiffEntry entry) {
        String oldPath = entry.getOldPath();
        String newPath = entry.getNewPath();
        if (!oldPath.equals(newPath)) {
            return oldPath + " -> " + newPath;
        }
        if ("/dev/null".equals(oldPath)) {
            return newPath;
        }
        return oldPath;
    }

    private String normalizeTrackingEntry(String pathSpec) {
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
        for (String segment : p.split("/")) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new GitException("Invalid path: " + pathSpec);
            }
        }
        // M-05: rely solely on syntactic trailing '/' (TOCTOU-safe); callers must
        // include '/' for directories
        Path resolved = plugin.serverRoot().resolve(p).normalize().toAbsolutePath();
        Path base = plugin.serverRoot().normalize().toAbsolutePath();
        if (!resolved.startsWith(base)) {
            throw new GitException("Path escapes server root: " + pathSpec);
        }
        return p;
    }

    private String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}