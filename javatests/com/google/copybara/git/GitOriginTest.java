/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.git;

import static com.google.common.truth.Truth.assertThat;
import static com.google.copybara.testing.FileSubjects.assertThatPath;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.copybara.authoring.Author;
import com.google.copybara.authoring.Authoring;
import com.google.copybara.authoring.Authoring.AuthoringMappingMode;
import com.google.copybara.Change;
import com.google.copybara.ChangeVisitable.VisitResult;
import com.google.copybara.Origin.Reader;
import com.google.copybara.RepoException;
import com.google.copybara.ValidationException;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.util.Glob;
import com.google.copybara.util.console.Message.MessageType;
import com.google.copybara.util.console.testing.TestingConsole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GitOriginTest {

  private static final String commitTime = "2037-02-16T13:00:00Z";
  private String url;
  private String ref;
  private GitOrigin origin;
  private Path remote;
  private Path checkoutDir;
  private String firstCommitRef;
  private OptionsBuilder options;
  private GitRepository repo;
  private final Authoring authoring = new Authoring(new Author("foo", "default@example.com"),
      AuthoringMappingMode.PASS_THRU, ImmutableSet.<String>of());
  
  @Rule public final ExpectedException thrown = ExpectedException.none();

  private TestingConsole console;
  private SkylarkTestExecutor skylark;

  @Before
  public void setup() throws Exception {
    options = new OptionsBuilder();
    console = new TestingConsole();
    options = new OptionsBuilder().setConsole(console);

    Path reposDir = Files.createTempDirectory("repos_repo");
    options.git.repoStorage = reposDir.toString();

    skylark = new SkylarkTestExecutor(options, GitModule.class);
    // Pass custom HOME directory so that we run an hermetic test and we
    // can add custom configuration to $HOME/.gitconfig.
    Path userHomeForTest = Files.createTempDirectory("home");
    options.setHomeDir(userHomeForTest.toString());

    createTestRepo(Files.createTempDirectory("remote"));
    checkoutDir = Files.createTempDirectory("checkout");

    url = "file://" + remote.toFile().getAbsolutePath();
    ref = "other";

    origin = origin();

    Files.write(remote.resolve("test.txt"), "some content".getBytes());
    repo.add().files("test.txt").run();
    git("commit", "-m", "first file", "--date", commitTime);
    String head = git("rev-parse", "HEAD");
    // Remove new line
    firstCommitRef = head.substring(0, head.length() -1);
  }

  private void createTestRepo(Path folder) throws IOException, RepoException {
    remote = folder;
    repo = GitRepository.initScratchRepo(/*verbose*/true, remote, options.general.getEnvironment());
  }

  private Reader<GitReference> newReader() {
    return origin.newReader(Glob.ALL_FILES, authoring);
  }

  private GitOrigin origin() throws ValidationException {
    return skylark.eval("result",
        String.format("result = git.origin(\n"
            + "    url = '%s',\n"
            + "    ref = '%s',\n"
            + ")", url, ref));
  }

  private String git(String... params) throws RepoException {
    return repo.git(remote, params).getStdout();
  }

  @Test
  public void testGitOrigin() throws Exception {
    origin = skylark.eval("result",
        "result = git.origin(\n"
            + "    url = 'https://my-server.org/copybara',\n"
            + "    ref = 'master',\n"
            + ")");
    assertThat(origin.toString())
        .isEqualTo(
            "GitOrigin{"
                + "repoUrl=https://my-server.org/copybara, "
                + "ref=master, "
                + "repoType=GIT"
                + "}");
  }

  @Test
  public void testGitOriginWithEmptyRef() throws Exception {
    origin = skylark.eval("result",
        "result = git.origin(\n"
            + "    url = 'https://my-server.org/copybara',\n"
            + ")");
    assertThat(origin.toString())
        .isEqualTo(
            "GitOrigin{"
                + "repoUrl=https://my-server.org/copybara, "
                + "ref=null, "
                + "repoType=GIT"
                + "}");
  }

  @Test
  public void testGerritOrigin() throws Exception {
    origin = skylark.eval("result",
        "result = git.gerrit_origin(\n"
            + "    url = 'https://gerrit-server.org/copybara',\n"
            + "    ref = 'master',\n"
            + ")");
    assertThat(origin.toString())
        .isEqualTo(
            "GitOrigin{"
                + "repoUrl=https://gerrit-server.org/copybara, "
                + "ref=master, "
                + "repoType=GERRIT"
                + "}");
  }

  @Test
  public void testGithubOrigin() throws Exception {
    origin = skylark.eval("result",
        "result = git.github_origin(\n"
            + "    url = 'https://github.com/copybara',\n"
            + "    ref = 'master',\n"
            + ")");
    assertThat(origin.toString())
        .isEqualTo(
            "GitOrigin{"
                + "repoUrl=https://github.com/copybara, "
                + "ref=master, "
                + "repoType=GITHUB"
                + "}");
  }

  @Test
  public void testInvalidGithubUrl() throws Exception {
    try {
      skylark.eval("result",
          "result = git.github_origin(\n"
              + "    url = 'https://foo.com/copybara',\n"
              + "    ref = 'master',\n"
              + ")");
      fail();
    } catch (ValidationException expected) {
      console.assertThat()
          .onceInLog(MessageType.ERROR, ".*Invalid Github URL: https://foo.com/copybara.*");
    }
  }

  @Test
  public void testCheckout() throws IOException, RepoException {
    // Check that we get can checkout a branch
    newReader().checkout(origin.resolve("master"), checkoutDir);
    Path testFile = checkoutDir.resolve("test.txt");

    assertThat(new String(Files.readAllBytes(testFile))).isEqualTo("some content");

    // Check that we track new commits that modify files
    Files.write(remote.resolve("test.txt"), "new content".getBytes());
    repo.add().files("test.txt").run();
    git("commit", "-m", "second commit");

    newReader().checkout(origin.resolve("master"), checkoutDir);

    assertThat(new String(Files.readAllBytes(testFile))).isEqualTo("new content");

    // Check that we track commits that delete files
    Files.delete(remote.resolve("test.txt"));
    git("rm", "test.txt");
    git("commit", "-m", "third commit");

    newReader().checkout(origin.resolve("master"), checkoutDir);

    assertThat(Files.exists(testFile)).isFalse();
  }

  @Test
  public void testGitOriginWithHook() throws Exception {
    Path hook = Files.createTempFile("script", "script");
    Files.write(hook, "touch hook.txt".getBytes(UTF_8));

    Files.setPosixFilePermissions(hook, ImmutableSet.<PosixFilePermission>builder()
        .addAll(Files.getPosixFilePermissions(hook))
        .add(PosixFilePermission.OWNER_EXECUTE).build());

    options.git.originCheckoutHook = hook.toAbsolutePath().toString();
    origin = origin();
    newReader().checkout(origin.resolve("master"), checkoutDir);
    assertThatPath(checkoutDir).containsFile("hook.txt", "");
  }

  @Test
  public void testGitOriginWithHookExitError() throws Exception {
    Path hook = Files.createTempFile("script", "script");
    Files.write(hook, "exit 1".getBytes(UTF_8));

    Files.setPosixFilePermissions(hook, ImmutableSet.<PosixFilePermission>builder()
        .addAll(Files.getPosixFilePermissions(hook))
        .add(PosixFilePermission.OWNER_EXECUTE).build());

    options.git.originCheckoutHook = hook.toAbsolutePath().toString();
    origin = origin();
    Reader<GitReference> reader = newReader();
    thrown.expect(RepoException.class);
    thrown.expectMessage("Error executing the git checkout hook");
    reader.checkout(origin.resolve("master"), checkoutDir);
  }

  @Test
  public void testCheckoutWithLocalModifications() throws IOException, RepoException {
    GitReference master = origin.resolve("master");
    Reader<GitReference> reader = newReader();
    reader.checkout(master, checkoutDir);
    Path testFile = checkoutDir.resolve("test.txt");

    assertThat(new String(Files.readAllBytes(testFile))).isEqualTo("some content");

    Files.delete(testFile);

    reader.checkout(master, checkoutDir);

    // The deletion in the checkoutDir should not matter, since we should override in the next
    // checkout
    assertThat(new String(Files.readAllBytes(testFile))).isEqualTo("some content");
  }

  @Test
  public void testCheckoutOfARef() throws IOException, RepoException {
    GitReference reference = origin.resolve(firstCommitRef);
    newReader().checkout(reference, checkoutDir);
    Path testFile = checkoutDir.resolve("test.txt");

    assertThat(new String(Files.readAllBytes(testFile))).isEqualTo("some content");
  }

  @Test
  public void testChanges() throws IOException, RepoException {
    // Need to "round" it since git doesn't store the milliseconds
    ZonedDateTime beforeTime = ZonedDateTime.now().minusSeconds(1);
    String author = "John Name <john@name.com>";
    singleFileCommit(author, "change2", "test.txt", "some content2");
    singleFileCommit(author, "change3", "test.txt", "some content3");
    singleFileCommit(author, "change4", "test.txt", "some content4");

    ImmutableList<Change<GitReference>> changes = newReader()
        .changes(origin.resolve(firstCommitRef), origin.resolve("HEAD"));

    assertThat(changes).hasSize(3);
    assertThat(changes.get(0).getMessage()).isEqualTo("change2\n");
    assertThat(changes.get(1).getMessage()).isEqualTo("change3\n");
    assertThat(changes.get(2).getMessage()).isEqualTo("change4\n");
    for (Change<GitReference> change : changes) {
      assertThat(change.getAuthor().getEmail()).isEqualTo("john@name.com");
      assertThat(change.getDateTime()).isAtLeast(beforeTime);
      assertThat(change.getDateTime()).isAtMost(ZonedDateTime.now().plusSeconds(1));
    }
  }

  @Test
  public void testNoChanges() throws IOException, RepoException {
    ImmutableList<Change<GitReference>> changes = newReader()
        .changes(origin.resolve(firstCommitRef), origin.resolve("HEAD"));

    assertThat(changes).isEmpty();
  }

  @Test
  public void testChange() throws IOException, RepoException {
    String author = "John Name <john@name.com>";
    singleFileCommit(author, "change2", "test.txt", "some content2");

    GitReference lastCommitRef = getLastCommitRef();
    Change<GitReference> change = newReader().change(lastCommitRef);

    assertThat(change.getAuthor().getEmail()).isEqualTo("john@name.com");
    assertThat(change.firstLineMessage()).isEqualTo("change2");
    assertThat(change.getReference().asString()).isEqualTo(lastCommitRef.asString());
  }

  @Test
  public void testChangeMultiLabel() throws IOException, RepoException {
    String commitMessage = ""
        + "I am a commit with a label happening twice\n"
        + "\n"
        + "foo: bar\n"
        + "\n"
        + "foo: baz\n";
    singleFileCommit("John Name <john@name.com>", commitMessage, "test.txt", "content");

    Change<GitReference> change = newReader().change(getLastCommitRef());
    // We keep the last label. The probability that the last one is a label and the first one
    // is just description is very high.
    assertThat(change.getLabels()).containsEntry("foo", "baz");
    console.assertThat()
        .onceInLog(MessageType.WARNING, "Possible duplicate label 'foo'"
            + " happening multiple times in commit. Keeping only the last value: 'baz'\n"
            + "  Discarded value: 'bar'");
  }

  @Test
  public void testNoChange() throws IOException, RepoException {
    // This is needed to initialize the local repo
    origin.resolve(firstCommitRef);

    thrown.expect(RepoException.class);
    thrown.expectMessage("Cannot find reference 'foo'");

    origin.resolve("foo");
  }

  @Test
  public void testVisit() throws IOException, RepoException {
    String author = "John Name <john@name.com>";
    singleFileCommit(author, "one", "test.txt", "some content1");
    singleFileCommit(author, "two", "test.txt", "some content2");
    singleFileCommit(author, "three", "test.txt", "some content3");
    GitReference lastCommitRef = getLastCommitRef();
    final List<Change<?>> visited = new ArrayList<>();
    newReader().visitChanges(lastCommitRef,
        input -> {
          visited.add(input);
          System.out.println(input.firstLineMessage().equals("three"));
          return input.firstLineMessage().equals("three")
              ? VisitResult.CONTINUE
              : VisitResult.TERMINATE;
        });

    assertThat(visited).hasSize(2);
    assertThat(visited.get(0).firstLineMessage()).isEqualTo("three");
    assertThat(visited.get(1).firstLineMessage()).isEqualTo("two");
  }

  @Test
  public void testVisitMerge() throws IOException, RepoException {
    createBranchMerge("John Name <john@name.com>");
    GitReference lastCommitRef = getLastCommitRef();
    final List<Change<?>> visited = new ArrayList<>();
    newReader().visitChanges(lastCommitRef,
        input -> {
          visited.add(input);
          return VisitResult.CONTINUE;
        });

    // We don't visit 'feature' branch since the visit is using --first-parent. Maybe we have
    // to revisit this in the future.
    assertThat(visited).hasSize(4);
    assertThat(visited.get(0).firstLineMessage()).isEqualTo("Merge branch 'feature'");
    assertThat(visited.get(1).firstLineMessage()).isEqualTo("master2");
    assertThat(visited.get(2).firstLineMessage()).isEqualTo("master1");
    assertThat(visited.get(3).firstLineMessage()).isEqualTo("first file");
  }

  /**
   * Check that we can overwrite the git url using the CLI option and that we show a message
   */
  @Test
  public void testGitUrlOverwrite() throws ValidationException, IOException, RepoException {
    createTestRepo(Files.createTempDirectory("cliremote"));
    git("init");
    Files.write(remote.resolve("cli_remote.txt"), "some change".getBytes());
    repo.add().files("cli_remote.txt").run();
    git("commit", "-m", "a change from somewhere");

    TestingConsole testConsole = new TestingConsole();
    options.setConsole(testConsole);
    origin = origin();

    String newUrl = "file://" + remote.toFile().getAbsolutePath();
    Reader<GitReference> reader = newReader();
    Change<GitReference> cliHead = reader.change(origin.resolve(newUrl));

    reader.checkout(cliHead.getReference(), checkoutDir);

    assertThat(cliHead.firstLineMessage()).isEqualTo("a change from somewhere");

    assertThatPath(checkoutDir)
        .containsFile("cli_remote.txt", "some change")
        .containsNoMoreFiles();

    testConsole.assertThat()
        .onceInLog(MessageType.WARNING,
            "Git origin URL overwritten in the command line as " + newUrl);
  }

  @Test
  public void testChangesMerge() throws IOException, RepoException {
    // Need to "round" it since git doesn't store the milliseconds
    ZonedDateTime beforeTime = ZonedDateTime.now().minusSeconds(1);

    String author = "John Name <john@name.com>";
    createBranchMerge(author);

    ImmutableList<Change<GitReference>> changes = newReader()
        .changes(origin.resolve(firstCommitRef), origin.resolve("HEAD"));

    assertThat(changes).hasSize(3);
    assertThat(changes.get(0).getMessage()).isEqualTo("master1\n");
    assertThat(changes.get(1).getMessage()).isEqualTo("master2\n");
    assertThat(changes.get(2).getMessage()).isEqualTo("Merge branch 'feature'\n");
    for (Change<GitReference> change : changes) {
      assertThat(change.getAuthor().getEmail()).isEqualTo("john@name.com");
      assertThat(change.getDateTime()).isAtLeast(beforeTime);
      assertThat(change.getDateTime()).isAtMost(ZonedDateTime.now().plusSeconds(1));
    }
  }

  public void createBranchMerge(String author) throws RepoException, IOException {
    git("branch", "feature");
    git("checkout", "feature");
    singleFileCommit(author, "change2", "test2.txt", "some content2");
    singleFileCommit(author, "change3", "test2.txt", "some content3");
    git("checkout", "master");
    singleFileCommit(author, "master1", "test.txt", "some content2");
    singleFileCommit(author, "master2", "test.txt", "some content3");
    git("merge", "master", "feature");
    // Change merge author
    git("commit", "--amend", "--author=" + author, "--no-edit");
  }

  @Test
  public void canReadTimestamp() throws IOException, RepoException {
    Files.write(remote.resolve("test2.txt"), "some more content".getBytes());
    repo.add().files("test2.txt").run();
    git("commit", "-m", "second file", "--date=1400110011");
    GitReference master = origin.resolve("master");
    Instant timestamp = master.readTimestamp();
    assertThat(timestamp).isNotNull();
    assertThat(timestamp.getEpochSecond()).isEqualTo(1400110011L);
  }

  @Test
  public void testColor() throws RepoException, IOException {
    git("config", "--global", "color.ui", "always");

    GitReference firstRef = origin.resolve(firstCommitRef);

    Files.write(remote.resolve("test.txt"), "new content".getBytes());
    repo.add().files("test.txt").run();
    git("commit", "-m", "second commit");
    GitReference secondRef = origin.resolve("HEAD");

    Reader<GitReference> reader = newReader();
    assertThat(reader.change(firstRef).getMessage()).contains("first file");
    assertThat(reader.changes(null, secondRef)).hasSize(2);
    assertThat(reader.changes(firstRef, secondRef)).hasSize(1);
  }

  @Test
  public void testGitOriginTag() throws Exception {
    git("tag", "-m", "This is a tag", "0.1");

    Instant instant = origin.resolve("0.1").readTimestamp();

    assertThat(instant).isEqualTo(Instant.parse(commitTime));
  }

  private GitReference getLastCommitRef() throws RepoException {
    String head = git("rev-parse", "HEAD");
    String lastCommit = head.substring(0, head.length() -1);
    return origin.resolve(lastCommit);
  }

  private void singleFileCommit(String author, String commitMessage, String fileName,
      String fileContent) throws IOException, RepoException {
    Files.write(remote.resolve(fileName), fileContent.getBytes(UTF_8));
    repo.add().files(fileName).run();
    git("commit", "-m", commitMessage, "--author=" + author);
  }
}
