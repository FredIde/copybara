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
import static org.junit.Assert.fail;

import com.google.copybara.Migration;
import com.google.copybara.RepoException;
import com.google.copybara.ValidationException;
import com.google.copybara.testing.OptionsBuilder;
import com.google.copybara.testing.SkylarkTestExecutor;
import com.google.copybara.util.console.testing.TestingConsole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class GitMirrorTest {

  @Rule
  public final ExpectedException thrown = ExpectedException.none();

  private OptionsBuilder options;
  private TestingConsole console;
  private SkylarkTestExecutor skylark;
  private GitRepository originRepo;
  private GitRepository destRepo;
  private Path workdir;

  @Before
  public void setup() throws Exception {
    workdir = Files.createTempDirectory("workdir");
    options = new OptionsBuilder();
    options.setWorkdirToRealTempDir();
    originRepo = GitRepository.initScratchRepo(/*verbose=*/true,
        options.general.getEnvironment());
    destRepo = bareRepo(Files.createTempDirectory("destinationFolder"));

    Path reposDir = Files.createTempDirectory("repos_repo");
    options.git.repoStorage = reposDir.toString();

    destRepo.initGitDir();
    console = new TestingConsole();
    options.setConsole(console);

    skylark = new SkylarkTestExecutor(options, GitModule.class);

    Files.write(originRepo.getWorkTree().resolve("test.txt"), "some content".getBytes());
    originRepo.add().files("test.txt").run();
    originRepo.git(originRepo.getWorkTree(), "commit", "-m", "first file");
    originRepo.git(originRepo.getWorkTree(), "branch", "other");
  }

  @Test
  public void testMirror() throws Exception {
    Migration mirror = loadMigration(""
        + "core.project('foo')\n"
        + "git.mirror("
        + "    name = 'default',"
        + "    origin = 'file://" + originRepo.getGitDir().toAbsolutePath() + "',"
        + "    destination = 'file://" + destRepo.getGitDir().toAbsolutePath() + "',"
            + ")",
        "default");
    mirror.run(workdir, /*sourceRef=*/null);
    String orig = originRepo.git(originRepo.getGitDir(), "show-ref").getStdout();
    String dest = destRepo.git(destRepo.getGitDir(), "show-ref").getStdout();
    assertThat(dest).isEqualTo(orig);
  }

  /**
   * Regression that test that if we use a local repo cache we prune when fetching.
   *
   * <p>'other' should never be present in destRepo since at the time of the migration it was
   * not present in the origin and destRepo was empty.
   */
  @Test
  public void testMirrorDeletedOrigin() throws Exception {
    GitRepository destRepo1 = bareRepo(Files.createTempDirectory("dest1"));
    destRepo1.initGitDir();

    String cfgContent = ""
        + "core.project('foo')\n"
        + "git.mirror("
        + "    name = 'one',"
        + "    origin = 'file://" + originRepo.getGitDir().toAbsolutePath() + "',"
        + "    destination = 'file://" + destRepo1.getGitDir().toAbsolutePath() + "',"
        + ")\n"
        + "git.mirror("
        + "    name = 'two',"
        + "    origin = 'file://" + originRepo.getGitDir().toAbsolutePath() + "',"
        + "    destination = 'file://" + destRepo.getGitDir().toAbsolutePath() + "',"
        + ")";

    loadMigration(cfgContent, "one").run(workdir, /*sourceRef=*/null);
    originRepo.simpleCommand("branch", "-D", "other");
    loadMigration(cfgContent, "two").run(workdir, /*sourceRef=*/null);

    checkRefDoesntExist("refs/heads/other");
  }

  private Migration loadMigration(String cfgContent, String name)
      throws IOException, ValidationException {
    return skylark.loadConfig(cfgContent).getMigration(name);
  }

  @Test
  public void testMirrorNoPrune() throws Exception {
    GitRepository destRepo1 = bareRepo(Files.createTempDirectory("dest1"));
    destRepo1.initGitDir();

    String cfg = ""
        + "core.project('foo')\n"
        + "git.mirror("
        + "    name = 'default',"
        + "    origin = 'file://" + originRepo.getGitDir().toAbsolutePath() + "',"
        + "    destination = 'file://" + destRepo.getGitDir().toAbsolutePath() + "',"
        + ")\n"
        + "";

    String other = originRepo.git(originRepo.getGitDir(), "show-ref", "refs/heads/other",
        "-s").getStdout();
    Migration migration = loadMigration(cfg, "default");
    migration.run(workdir, /*sourceRef=*/null);
    originRepo.simpleCommand("branch", "-D", "other");
    migration.run(workdir, /*sourceRef=*/null);
    assertThat(destRepo.git(destRepo.getGitDir(), "show-ref", "refs/heads/other", "-s").getStdout())
        .isEqualTo(other);
  }

  @Test
  public void testMirrorPrune() throws Exception {
    GitRepository destRepo1 = bareRepo(Files.createTempDirectory("dest1"));
    destRepo1.initGitDir();

    String cfg = ""
        + "core.project('foo')\n"
        + "git.mirror("
        + "    name = 'default',"
        + "    origin = 'file://" + originRepo.getGitDir().toAbsolutePath() + "',"
        + "    destination = 'file://" + destRepo.getGitDir().toAbsolutePath() + "',"
        + "    prune = True,"
        + ")\n"
        + "";

    Migration migration = loadMigration(cfg, "default");
    migration.run(workdir, /*sourceRef=*/null);
    originRepo.simpleCommand("branch", "-D", "other");
    migration.run(workdir, /*sourceRef=*/null);

    checkRefDoesntExist("refs/heads/other");
  }

  private GitRepository bareRepo(Path path) throws IOException {
    return GitRepository.bareRepo(path, options.general.getEnvironment(),
        options.general.isVerbose());
  }

  @Test
  public void testMirrorCustomRefspec() throws Exception {
    String cfg = ""
        + "core.project('foo')\n"
        + "git.mirror("
        + "    name = 'default',"
        + "    origin = 'file://" + originRepo.getGitDir().toAbsolutePath() + "',"
        + "    destination = 'file://" + destRepo.getGitDir().toAbsolutePath() + "',"
        + "    refspecs = ["
        + "        'refs/heads/master:refs/heads/origin_master'"
        + "    ]"
        + ")";
    Migration mirror = loadMigration(cfg, "default");
    mirror.run(workdir, /*sourceRef=*/null);
    String origMaster = originRepo.git(originRepo.getGitDir(), "show-ref", "master", "-s")
        .getStdout();
    String destMaster = destRepo.git(destRepo.getGitDir(), "show-ref", "origin_master", "-s")
        .getStdout();
    assertThat(destMaster).isEqualTo(origMaster);
    checkRefDoesntExist("refs/heads/master");
    checkRefDoesntExist("refs/heads/other");
  }

  private void checkRefDoesntExist(String ref) throws RepoException {
    assertThat(destRepo.git(destRepo.getGitDir(), "show-ref").getStdout())
        .doesNotContain(" " + ref + "\n");
  }

  @Test
  public void testMirrorConflict() throws Exception {
    Migration mirror = prepareForConflict();
    try {
      mirror.run(workdir, /*sourceRef=*/null);
      fail();
    } catch (RepoException e) {
      assertThat(e.getMessage()).contains("[rejected]");
    }
  }

  @Test
  public void testMirrorNoConflictIfForce() throws Exception {
    options.gitMirrorOptions.forcePush = true;
    Migration mirror = prepareForConflict();
    mirror.run(workdir, /*sourceRef=*/null);
  }

  private Migration prepareForConflict() throws IOException, ValidationException, RepoException {
    String cfg = ""
        + "core.project('foo')\n"
        + "git.mirror("
        + "    name = 'default',"
        + "    origin = 'file://" + originRepo.getGitDir().toAbsolutePath() + "',"
        + "    destination = 'file://" + destRepo.getGitDir().toAbsolutePath() + "',"
        + ")";
    GitRepository other = GitRepository.initScratchRepo(/*verbose=*/true,
        options.general.getEnvironment());
    Files.write(other.getWorkTree().resolve("test2.txt"), "some content".getBytes());
    other.add().files("test2.txt").run();
    other.git(other.getWorkTree(), "commit", "-m", "another file");
    other.git(other.getWorkTree(), "branch", "other");
    other.git(other.getWorkTree(), "push", "file://" + destRepo.getGitDir(), "+refs/*:refs/*");
    return loadMigration(cfg, "default");
  }
}
