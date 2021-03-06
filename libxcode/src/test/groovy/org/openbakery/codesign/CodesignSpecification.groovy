package org.openbakery.codesign

import org.apache.commons.configuration.plist.XMLPropertyListConfiguration
import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.openbakery.CommandRunner
import org.openbakery.configuration.ConfigurationFromMap
import org.openbakery.configuration.ConfigurationFromPlist
import org.openbakery.util.PlistHelper
import org.openbakery.test.ApplicationDummy
import org.openbakery.xcode.Type
import org.openbakery.xcode.XcodeFake
import spock.lang.Specification

class CodesignSpecification extends  Specification {

	Codesign codesign
	ApplicationDummy applicationDummy
	File tmpDirectory
	CommandRunner commandRunner = Mock(CommandRunner)
	PlistHelper plistHelper
	File keychainPath
	CodesignParameters parameters

	void setup() {
		tmpDirectory = new File(System.getProperty("java.io.tmpdir"), "gxp-test")
		applicationDummy = new ApplicationDummy(tmpDirectory)

		keychainPath = new File(tmpDirectory, "gradle-test.keychain")
		plistHelper = new PlistHelper(new CommandRunner())

		File entitlementsFile = new File(applicationDummy.payloadAppDirectory, "archived-expanded-entitlements.xcent")
		plistHelper.create(entitlementsFile)
		plistHelper.addValueForPlist(entitlementsFile, "application-identifier", "AAAAAAAAAA.org.openbakery.test.Example")
		plistHelper.addValueForPlist(entitlementsFile, "keychain-access-groups", ["AAAAAAAAAA.org.openbakery.test.Example", "AAAAAAAAAA.org.openbakery.test.ExampleWidget", "BBBBBBBBBB.org.openbakery.Foobar"])

		parameters = new CodesignParameters()
		parameters.signingIdentity = ""
		parameters.keychain = keychainPath
		parameters.mobileProvisionFiles = applicationDummy.mobileProvisionFile
		parameters.type = Type.iOS

		codesign = new Codesign(
						new XcodeFake(),
						parameters,
						commandRunner,
						plistHelper)

	}

	def cleanup() {
		applicationDummy.cleanup()
		tmpDirectory.deleteDir()
	}

	void mockEntitlementsFromProvisioningProfile(File provisioningProfile) {
		def commandList = ['security', 'cms', '-D', '-i', provisioningProfile.absolutePath]
		String result = new File('../libtest/src/main/Resource/entitlements.plist').text
		commandRunner.runWithResult(commandList) >> result
		String basename = FilenameUtils.getBaseName(provisioningProfile.path)
		File plist = new File(System.getProperty("java.io.tmpdir") + "/provision_" + basename + ".plist")
		commandList = ['/usr/libexec/PlistBuddy', '-x', plist.absolutePath, '-c', 'Print Entitlements']
		commandRunner.runWithResult(commandList) >> result
	}



	def "create keychain access groupds from xcent file"() {
		given:
		applicationDummy.create()

		when:
		File xcentFile = codesign.getXcentFile(applicationDummy.payloadAppDirectory)
		List<String> keychainAccessGroup = codesign.getKeychainAccessGroupFromEntitlements(new ConfigurationFromPlist(xcentFile))

		then:
		keychainAccessGroup.size() == 3
		keychainAccessGroup[0] == "\$(AppIdentifierPrefix)org.openbakery.test.Example"
		keychainAccessGroup[1] == "\$(AppIdentifierPrefix)org.openbakery.test.ExampleWidget"
		keychainAccessGroup[2] == "BBBBBBBBBB.org.openbakery.Foobar"
	}

	def "create keychain access groups, has not application identifiert"() {
		given:
		applicationDummy.create()
		def entitlements = [
						'com.apple.security.application-groups': ['group.com.example.MyApp'],
						'keychain-access-groups'               : [
										'$(AppIdentifierPrefix)com.example.MyApp'
						]

		]

		when:
		List<String> keychainAccessGroup = codesign.getKeychainAccessGroupFromEntitlements(new ConfigurationFromMap(entitlements))

		then:
		keychainAccessGroup.size() == 1
		keychainAccessGroup[0] == "\$(AppIdentifierPrefix)com.example.MyApp"
	}


	def "create entitlements with keychain access groups"() {
		given:
		applicationDummy.create()


		mockEntitlementsFromProvisioningProfile(applicationDummy.mobileProvisionFile.first())

		File xcentFile = codesign.getXcentFile(applicationDummy.payloadAppDirectory)
		when:
		File entitlementsFile = codesign.createEntitlementsFile("org.openbakery.test.Example", new ConfigurationFromPlist(xcentFile))

		then:
		entitlementsFile.exists()
		entitlementsFile.text.contains("AAAAAAAAAA.org.openbakery.test.Example")
		entitlementsFile.text.contains("AAAAAAAAAA.org.openbakery.test.ExampleWidget")
	}


	def "use entitlements file"() {
		def commandList
		given:
		applicationDummy.create()
		File useEntitlementsFile = new File(tmpDirectory, "MyCustomEntitlements.plist")
		FileUtils.writeStringToFile(useEntitlementsFile, "foobar")
		parameters.entitlementsFile = useEntitlementsFile

		when:
		codesign.sign(new File("dummy")) // .createEntitlementsFile("org.openbakery.test.Example", new ConfigurationFromPlist(xcentFile))
		then:

		1 * commandRunner.run(_, _) >> { arguments -> commandList = arguments[0] }
		commandList.contains("/usr/bin/codesign")
		commandList.contains(useEntitlementsFile.absolutePath)

		//entitlementsFile.path.endsWith("MyCustomEntitlements.plist")
		cleanup:
		useEntitlementsFile.delete()
	}

	def "use entitlements file does not exist"() {
		given:
		applicationDummy.create()
		when:
		parameters.entitlementsFile = new File(tmpDirectory, "MyCustomEntitlements.plist")
		codesign.sign(new File("dummy"))
		then:
		thrown(IllegalArgumentException)
	}


	def "create entitlements were merged with xcent"() {
		given:
		applicationDummy.create()

		mockEntitlementsFromProvisioningProfile(applicationDummy.mobileProvisionFile.first())
		File xcent =  new File(applicationDummy.payloadAppDirectory, "archived-expanded-entitlements.xcent")
		FileUtils.copyFile(new File("../plugin/src/test/Resource/archived-expanded-entitlements.xcent"), xcent)

		when:

		File entitlementsFile = codesign.createEntitlementsFile("org.openbakery.test.Example", new ConfigurationFromPlist(xcent))
		XMLPropertyListConfiguration entitlements = new XMLPropertyListConfiguration(entitlementsFile)

		then:
		entitlementsFile.exists()
		entitlements.getString("com..apple..developer..default-data-protection") == "NSFileProtectionComplete"
        entitlements.getStringArray("com..apple..developer..associated-domains").length == 1
		entitlements.getStringArray("com..apple..developer..associated-domains").contains('webcredentials:example.com') == true
	}

	def "create entitlements and merge with settings from signing"() {
		def commandList
		File entitlementsFile
		XMLPropertyListConfiguration entitlements

		given:
		File bundle = applicationDummy.create()
		mockEntitlementsFromProvisioningProfile(applicationDummy.mobileProvisionFile.first())

		parameters.entitlements = [
						"com.apple.developer.associated-domains"     : ["webcredentials:example.com"],
						"com.apple.developer.default-data-protection": "NSFileProtectionComplete",
						"com.apple.security.application-groups"      : [],
						"com.apple.developer.siri"                   : true
		]

		when:
		codesign.sign(bundle)

		then:

		1 * commandRunner.run(_, _) >> {
			arguments ->
				commandList = arguments[0]
				if (commandList.size() > 3) {
					entitlementsFile = new File(commandList[3])
					entitlements = new XMLPropertyListConfiguration(entitlementsFile)
				}
		}
		commandList.contains("/usr/bin/codesign")
		commandList[2] == "--entitlements"
		entitlementsFile.exists()
		entitlements.getString("com..apple..developer..default-data-protection") == "NSFileProtectionComplete"
		entitlements.getBoolean("com..apple..developer..siri") == true
	}


	def "codesign if identity is null"() {
		def commandList
		parameters = new CodesignParameters()
		codesign.codesignParameters = parameters

		given:
		File bundle = applicationDummy.create()

		when:
		codesign.sign(bundle)

		then:
		1 * commandRunner.run(_, _) >> {
			arguments ->
				commandList = arguments[0]
		}
		commandList == ["/usr/bin/codesign", "--force", "--sign", "-", "--verbose", bundle.absolutePath ]
	}


	def "codesign if identity is null and bundle identifier is present"() {
		def commandList

		def plistHelper = Mock(PlistHelper)
		plistHelper.getValueFromPlist(_, "CFBundleIdentifier") >> "com.example.Example"

		parameters = new CodesignParameters()
		parameters.type = Type.iOS

		codesign.codesignParameters = parameters

		given:
		File bundle = applicationDummy.create()

		when:
		codesign.sign(bundle)

		then:
		1 * commandRunner.run(_, _) >> {
			arguments ->
				commandList = arguments[0]
		}
		commandList == ["/usr/bin/codesign", "--force", "--sign", "-", "--verbose", bundle.absolutePath ]
	}


}
