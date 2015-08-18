package org.openbakery.appstore

import org.apache.commons.io.FileUtils
import org.gmock.GMockController
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.openbakery.CommandRunner
import org.openbakery.XcodePlugin
import org.junit.After
import org.junit.Before
import org.junit.Test

import static org.hamcrest.Matchers.anything

/**
 * Created by rene on 08.01.15.
 */
class AppstoreValidateTaskTest {


	Project project
	AppstoreValidateTask task
	File infoPlist

	GMockController mockControl
	CommandRunner commandRunnerMock
	File ipaBundle;

	@Before
	void setup() {
		mockControl = new GMockController()
		commandRunnerMock = mockControl.mock(CommandRunner)

		File projectDir = new File(System.getProperty("java.io.tmpdir"), "gradle-xcodebuild")
		project = ProjectBuilder.builder().withProjectDir(projectDir).build()
		project.buildDir = new File(projectDir, 'build').absoluteFile
		project.apply plugin: org.openbakery.XcodePlugin

		project.xcodebuild.xcodePath = "/Application/Xcode.app"

		task = project.getTasks().getByPath(XcodePlugin.APPSTORE_VALIDATE_TASK_NAME)

		task.setProperty("commandRunner", commandRunnerMock)


		ipaBundle = new File(project.buildDir, "package/Test.ipa")
		FileUtils.writeStringToFile(ipaBundle, "dummy")

	}

	@After
	void cleanUp() {
		FileUtils.deleteDirectory(project.projectDir)
	}

	@Test(expected = IllegalStateException.class)
	void ipaMissing() {
		FileUtils.deleteDirectory(project.projectDir)

		task.executeTask()

	}

	@Test
	void testValidate() {

		project.appstore.username = "me@example.com"
		project.appstore.password = "1234"

		def command = "/Application/Xcode.app/Contents/Applications/Application Loader.app/Contents/Frameworks/ITunesSoftwareService.framework/Support/altool"

		List<String> commandList
		commandList?.clear()
		commandList = [command, "--validate-app", "--username", "me@example.com", "--password", "1234", "--file", ipaBundle.absolutePath]
		commandRunnerMock.run(commandList, anything()).times(1)


		mockControl.play {
			task.executeTask()
		}
	}

	@Test(expected = IllegalArgumentException.class)
	void testPasswordMissing() {
		project.appstore.username = "me@example.com"

		mockControl.play {
			task.executeTask()
		}
	}

	@Test(expected = IllegalArgumentException.class)
	void testUsernameMissing() {

		mockControl.play {
			task.executeTask()
		}
	}
}
