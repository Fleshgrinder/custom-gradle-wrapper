@file:Suppress("UnstableApiUsage")

package com.fleshgrinder.gradle.wrapper

import org.gradle.api.tasks.wrapper.Wrapper as GradleWrapperTask
import java.io.File
import java.io.FileNotFoundException
import java.util.*
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.internal.plugins.StartScriptGenerator
import org.gradle.api.internal.properties.GradleProperties
import org.gradle.api.invocation.Gradle
import org.gradle.api.logging.LogLevel
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.*
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitivity.RELATIVE
import org.gradle.api.tasks.options.Option
import org.gradle.api.tasks.options.OptionValues
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType.BIN
import org.gradle.api.tasks.wrapper.Wrapper.PathBase
import org.gradle.api.tasks.wrapper.Wrapper.PathBase.GRADLE_USER_HOME
import org.gradle.initialization.GradleUserHomeDirProvider
import org.gradle.kotlin.dsl.property
import org.gradle.util.GradleVersion
import org.gradle.work.DisableCachingByDefault
import org.gradle.wrapper.*
import org.gradle.wrapper.Download.UNKNOWN_VERSION
import org.gradle.wrapper.Install.DEFAULT_DISTRIBUTION_PATH
import org.gradle.wrapper.WrapperExecutor.*

@DisableCachingByDefault(because = "Updating the wrapper is not worth caching.")
public abstract class Wrapper @Inject constructor(
    private val fs: FileOperations,
    private val gradle: Gradle,
    private val gradleUserHome: GradleUserHomeDirProvider,
    gradleProperties: GradleProperties,
    private val objects: ObjectFactory,
    private val providers: ProviderFactory,
) : DefaultTask() {
    private inline fun <reified T> property(): Property<T> =
        objects.property<T>().apply {
            disallowUnsafeRead()
            finalizeValueOnRead()
        }

    private inline fun <reified T> property(value: T?): Property<T> =
        property<T>().convention(value)

    private inline fun <reified T> property(noinline value: () -> T?): Property<T> =
        property<T>().convention(providers.provider(value))

    private val properties by lazy(propertiesFile) {
        Properties().apply {
            try {
                propertiesFile.inputStream().use { stream ->
                    stream.channel.lock(0, Long.MAX_VALUE, true).use { _ ->
                        load(stream)
                    }
                }
            } catch (_: FileNotFoundException) {
                // This is a new project that does not have a wrapper yet. In
                // this case we use defaults for everything.
            }
        }
    }

    /**
     * @see GradleWrapperTask.getArchiveBase
     * @see GradleWrapperTask.setArchiveBase
     */
    @get:Input
    public val archiveBase: Property<PathBase> = property {
        properties[ZIP_STORE_BASE_PROPERTY]
            ?.toString()
            ?.toUpperCase(Locale.ROOT)
            ?.let(PathBase::valueOf)
            ?: GRADLE_USER_HOME
    }

    /**
     * @see GradleWrapperTask.getArchivePath
     * @see GradleWrapperTask.setArchivePath
     */
    @get:Input
    public val archivePath: Property<String> = property {
        properties[ZIP_STORE_PATH_PROPERTY]?.toString()
            ?: DEFAULT_DISTRIBUTION_PATH
    }

    @get:Optional
    @get:Input
    internal abstract val artifacts: SetProperty<ResolvedArtifactResult>

    /**
     * @see GradleWrapperTask.getDistributionBase
     * @see GradleWrapperTask.setDistributionBase
     */
    @get:Input
    public val distributionBase: Property<PathBase> = property {
        properties[DISTRIBUTION_BASE_PROPERTY]
            ?.toString()
            ?.toUpperCase(Locale.ROOT)
            ?.let(PathBase::valueOf)
            ?: GRADLE_USER_HOME
    }

    /**
     * @see GradleWrapperTask.getDistributionPath
     * @see GradleWrapperTask.setDistributionPath
     */
    @get:Input
    public val distributionPath: Property<String> = property {
        properties[DISTRIBUTION_PATH_PROPERTY]?.toString()
            ?: DEFAULT_DISTRIBUTION_PATH
    }

    /**
     * @see distTypes
     * @see GradleWrapperTask.getDistributionType
     * @see GradleWrapperTask.setDistributionType
     */
    @get:Option(
        option = "dist-type",
        description = "Specifies the type of the distribution to download.",
    )
    @get:Input
    public val distType: Property<DistributionType> = property {
        gradleProperties.find(TYPE_PROPERTY)
            ?.toString()
            ?.toUpperCase(Locale.ROOT)
            ?.let(DistributionType::valueOf)
            ?: BIN
    }

    /**
     * @see distType
     * @see GradleWrapperTask.getAvailableDistributionTypes
     */
    @get:OptionValues("dist-type")
    public val distTypes: List<DistributionType>
        get() = DistributionType.values().toList()

    /**
     * @see GradleWrapperTask.getDistributionUrl
     * @see GradleWrapperTask.setDistributionUrl
     */
    @get:Option(
        option = "dist-url",
        description = "Specifies the absolute URL of the distribution to download.",
    )
    @get:Input
    public val distUrl: Property<String> = property {
        properties[DISTRIBUTION_URL_PROPERTY]?.toString()
    }

    /**
     * @see GradleWrapperTask.getDistributionSha256Sum
     * @see GradleWrapperTask.setDistributionSha256Sum
     */
    @get:Option(
        option = "dist-sha256",
        description = "Specifies the SHA256 checksum of the distribution to download.",
    )
    @get:Optional
    @get:Input
    public val distSha256: Property<String> = property {
        properties[DISTRIBUTION_SHA_256_SUM]?.toString()
    }

    /**
     * @see GradleWrapperTask.getGradleVersion
     * @see GradleWrapperTask.setGradleVersion
     */
    @get:Option(
        option = "dist-version",
        description = "Specifies the version of the distribution to download.",
    )
    @get:Input
    public val distVersion: Property<String> = property {
        (gradleProperties.find(VERSION_PROPERTY)
            ?: GradleVersion.current()).toString()
    }

    @get:Option(
        option = "self-update",
        description = "Specifies if the distribution should be updated to its latest available version.",
    )
    @get:Input
    public val selfUpdate: Property<Boolean> = property(false)

    /**
     * @see GradleWrapperTask.getScriptFile
     * @see GradleWrapperTask.setScriptFile
     */
    @get:OutputFile
    @get:PathSensitive(RELATIVE)
    public val unixScript: File
        get() = fs.file("gradlew")

    /**
     * @see GradleWrapperTask.getBatchScript
     */
    @get:OutputFile
    @get:PathSensitive(RELATIVE)
    public val windowsScript: File
        get() = fs.file("gradlew.bat")

    /**
     * @see GradleWrapperTask.getJarFile
     * @see GradleWrapperTask.setJarFile
     */
    @get:OutputFile
    @get:PathSensitive(RELATIVE)
    public val jarFile: File
        get() = fs.file("gradle/wrapper/gradle-wrapper.jar")

    /**
     * @see GradleWrapperTask.getPropertiesFile
     */
    @get:OutputFile
    @get:PathSensitive(RELATIVE)
    public val propertiesFile: File
        get() = fs.file("gradle/wrapper/gradle-wrapper.properties")

    @TaskAction
    public fun execute() {
        StartScriptGenerator().apply {
            setApplicationName("Gradle")
            setMainClassName(GradleWrapperMain::class.java.name)
            setClasspath(listOf(jarFile.path))
            setOptsEnvironmentVar("GRADLE_OPTS")
            setExitEnvironmentVar("GRADLE_EXIT_CONSOLE")
            setAppNameSystemProperty("org.gradle.appname")
            setScriptRelPath(unixScript.name)
            setDefaultJvmOpts(listOf("-Xmx64m", "-Xms64m"))
            generateUnixScript(unixScript)
            generateWindowsScript(windowsScript)
        }

        val logger = Logger(gradle.startParameter.logLevel == LogLevel.QUIET)
        Install(
            logger,
            Download(logger, "gradlew", UNKNOWN_VERSION),
            PathAssembler(gradleUserHome.gradleUserHomeDirectory, fs.file(".")),
        )
    }
}
