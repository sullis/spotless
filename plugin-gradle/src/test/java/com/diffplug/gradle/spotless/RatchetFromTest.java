/*
 * Copyright 2016-2020 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.gradle.spotless;

import java.util.Date;
import java.util.Objects;
import java.util.TimeZone;

import org.assertj.core.api.Assertions;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Test;

public class RatchetFromTest extends GradleIntegrationHarness {
	private static final String TEST_PATH = "src/markdown/test.md";

	@Test
	public void singleProjectExhaustive() throws Exception {
		try (Git git = Git.init().setDirectory(rootFolder()).call()) {
			setFile("build.gradle").toLines(
					"plugins { id 'com.diffplug.gradle.spotless' }",
					"spotless {",
					"  ratchetFrom 'baseline'",
					"  format 'misc', {",
					"    target 'src/markdown/*.md'",
					"    custom 'lowercase', { str -> str.toLowerCase() }",
					"    bumpThisNumberIfACustomStepChanges(1)",
					"  }",
					"}");
			setFile(TEST_PATH).toContent("HELLO");
			git.add().addFilepattern(TEST_PATH).call();
			git.commit().setMessage("Initial state").call();
			// tag this initial state as the baseline for spotless to ratchet from
			git.tag().setName("baseline").call();

			// so at this point we have test.md, and it would normally be dirty,
			// but because it is unchanged, spotless says it is clean
			assertClean();

			// but if we change it so that it is not clean, spotless will now say it is dirty
			setFile(TEST_PATH).toContent("HELLO WORLD");
			assertDirty();
			gradleRunner().withArguments("spotlessApply").build();
			assertFile(TEST_PATH).hasContent("hello world");

			// but if we make it unchanged again, it goes back to being clean
			setFile(TEST_PATH).toContent("HELLO");
			assertClean();

			// and if we make the index dirty
			setFile(TEST_PATH).toContent("HELLO WORLD");
			git.add().addFilepattern(TEST_PATH).call();
			{
				// and the content dirty in the same way, then it's dirty
				assertDirty();
				// if we make the content something else dirty, then it's dirty
				setFile(TEST_PATH).toContent("HELLO MOM");
				assertDirty();
				// if we make the content unchanged, even though index it and index are dirty, then it's clean
				setFile(TEST_PATH).toContent("HELLO");
				assertClean();
				// if we delete the file, but it's still in the index, then it's clean
				setFile(TEST_PATH).deleted();
				assertClean();
			}
			// if we remove the file from the index
			git.rm().addFilepattern(TEST_PATH).setCached(true).call();
			{
				// and it's gone in real life too, then it's clean
				assertClean();
				// if the content is there and unchanged, then it's clean
				setFile(TEST_PATH).toContent("HELLO");
				assertClean();
				// if the content is dirty, then it's dirty
				setFile(TEST_PATH).toContent("HELLO WORLD");
				assertDirty();
			}

			// new files always get checked
			setFile("new.md").toContent("HELLO");
			{
				assertDirty();
				// even if they are added
				git.add().addFilepattern("new.md").call();
				assertDirty();
			}
		}
	}

	private void assertClean() throws Exception {
		gradleRunner().withArguments("spotlessCheck").build();
	}

	private void assertDirty() throws Exception {
		gradleRunner().withArguments("spotlessCheck").buildAndFail();
	}

	private BuildResultAssertion assertPass(String... tasks) throws Exception {
		return new BuildResultAssertion(gradleRunner().withGradleVersion("6.0").withArguments(tasks).build());
	}

	private BuildResultAssertion assertFail(String... tasks) throws Exception {
		return new BuildResultAssertion(gradleRunner().withGradleVersion("6.0").withArguments(tasks).buildAndFail());
	}

	private static final String BASELINE_ROOT = "ebb03d6940ee0254010e71917735efa203c27e16";
	private static final String BASELINE_CLEAN = "65fdd75c1ae00c0646f6487d68c44ddca51f0841";
	private static final String BASELINE_DIRTY = "4cfc3358ccbf186738b82a60276b1e5306bc3870";

	@Test
	public void multiProject() throws Exception {
		try (Git git = Git.init().setDirectory(rootFolder()).call()) {
			setFile("settings.gradle").toLines(
					"plugins {",
					"  id 'com.diffplug.gradle.spotless' apply false",
					"}",
					"include 'clean'",
					"include 'dirty'",
					"include 'added'");
			setFile("spotless.gradle").toLines(
					"apply plugin: 'com.diffplug.gradle.spotless'",
					"spotless {",
					"  ratchetFrom 'master'",
					"  format 'misc', {",
					"    target 'src/markdown/*.md'",
					"    custom 'lowercase', { str -> str.toLowerCase() }",
					"    bumpThisNumberIfACustomStepChanges(1)",
					"  }",
					"}");
			setFile(".gitignore").toContent("build/\n.gradle\n");
			setFile("build.gradle").toContent("apply from: rootProject.file('spotless.gradle') // root");
			setFile(TEST_PATH).toContent("HELLO");
			setFile("clean/build.gradle").toContent("apply from: rootProject.file('spotless.gradle') // clean");
			setFile("clean/" + TEST_PATH).toContent("HELLO");
			setFile("dirty/build.gradle").toContent("apply from: rootProject.file('spotless.gradle') // dirty");
			setFile("dirty/" + TEST_PATH).toContent("HELLO");
			RevCommit baseline = addAndCommit(git);

			ObjectId cleanFolder = TreeWalk.forPath(git.getRepository(), "clean", baseline.getTree()).getObjectId(0);
			ObjectId dirtyFolder = TreeWalk.forPath(git.getRepository(), "dirty", baseline.getTree()).getObjectId(0);

			Assertions.assertThat(baseline.getTree().toObjectId()).isEqualTo(ObjectId.fromString(BASELINE_ROOT));
			Assertions.assertThat(cleanFolder).isEqualTo(ObjectId.fromString(BASELINE_CLEAN));
			Assertions.assertThat(dirtyFolder).isEqualTo(ObjectId.fromString(BASELINE_DIRTY));

			assertPass("spotlessCheck")
					.outcome(":spotlessCheck", TaskOutcome.SUCCESS)
					.outcome(":clean:spotlessCheck", TaskOutcome.SUCCESS)
					.outcome(":dirty:spotlessCheck", TaskOutcome.SUCCESS);

			setFile("added/build.gradle").toContent("apply from: rootProject.file('spotless.gradle') // added");
			setFile("added/" + TEST_PATH).toContent("HELLO");

			TreeWalk isNull = TreeWalk.forPath(git.getRepository(), "added", baseline.getTree());
			Assertions.assertThat(isNull).isNull();

			assertPass("spotlessMisc")
					.outcome(":spotlessMisc", TaskOutcome.UP_TO_DATE)
					.outcome(":clean:spotlessMisc", TaskOutcome.UP_TO_DATE)
					.outcome(":dirty:spotlessMisc", TaskOutcome.UP_TO_DATE)
					.outcome(":added:spotlessMisc", TaskOutcome.SUCCESS);
			assertFail(":added:spotlessCheck");
			assertPass(":added:spotlessApply");

			// now dirty is "git dirty" and "format dirty"
			setFile("dirty/" + TEST_PATH).toContent("HELLO WORLD");
			assertFail(":dirty:spotlessCheck")
					.outcome(":dirty:spotlessMisc", TaskOutcome.SUCCESS);
			assertPass("spotlessApply")
					.outcome(":dirty:spotlessMisc", TaskOutcome.UP_TO_DATE);
			// now it is "git dirty" but "format clean"
			assertPass("spotlessCheck");
			// and every single task is up-to-date
			assertPass("spotlessCheck")
					.outcome(":spotlessMisc", TaskOutcome.UP_TO_DATE)
					.outcome(":clean:spotlessMisc", TaskOutcome.UP_TO_DATE)
					.outcome(":dirty:spotlessMisc", TaskOutcome.UP_TO_DATE)
					.outcome(":added:spotlessMisc", TaskOutcome.UP_TO_DATE);

			RevCommit next = addAndCommit(git);
			Assertions.assertThat(next.getTree().toObjectId()).isNotEqualTo(baseline.getTree().toObjectId());
			// if we commit to master (the baseline), then tasks will be out of date only because the baseline changed
			// TO REPEAAT:
			// - everything was up-to-date
			// - we pressed "commit", which didn't change the files, just the baseline
			// - and that causes spotless to be out-of-date on all tasks

			ObjectId nextCleanFolder = TreeWalk.forPath(git.getRepository(), "clean", next.getTree()).getObjectId(0);
			ObjectId nextDirtyFolder = TreeWalk.forPath(git.getRepository(), "dirty", next.getTree()).getObjectId(0);
			Assertions.assertThat(nextCleanFolder).isEqualTo(cleanFolder);    // which is too bad, becuase the baseline for clean didn't change
			Assertions.assertThat(nextDirtyFolder).isNotEqualTo(dirtyFolder); // only the baseline for dirty

			// check will still pass, but the tasks are all out of date
			assertPass("spotlessCheck")
					.outcome(":spotlessMisc", TaskOutcome.SUCCESS)
					.outcome(":clean:spotlessMisc", TaskOutcome.SUCCESS)	// with up-to-dateness based on subtree, this could be UP-TO-DATE
					.outcome(":dirty:spotlessMisc", TaskOutcome.SUCCESS)
					.outcome(":added:spotlessMisc", TaskOutcome.SUCCESS);
		}
	}

	public static class BuildResultAssertion {
		BuildResult result;

		BuildResultAssertion(BuildResult result) {
			this.result = Objects.requireNonNull(result);
		}

		public BuildResultAssertion outcome(String taskPath, TaskOutcome expected) {
			TaskOutcome actual = result.getTasks().stream()
					.filter(task -> task.getPath().equals(taskPath))
					.findAny().get().getOutcome();
			Assertions.assertThat(actual).isEqualTo(expected);
			return this;
		}
	}

	private RevCommit addAndCommit(Git git) throws NoFilepatternException, GitAPIException {
		PersonIdent emptyPerson = new PersonIdent("jane doe", "jane@doe.com", new Date(0), TimeZone.getTimeZone("UTC"));
		git.add().addFilepattern(".").call();
		RevCommit commit = git.commit().setMessage("baseline")
				.setCommitter(emptyPerson)
				.setAuthor(emptyPerson)
				.call();
		return commit;
	}
}
