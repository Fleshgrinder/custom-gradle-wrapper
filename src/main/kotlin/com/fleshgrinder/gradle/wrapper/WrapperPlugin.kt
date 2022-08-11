@file:Suppress("UnstableApiUsage")

package com.fleshgrinder.gradle.wrapper

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.initialization.Settings
import org.gradle.api.internal.properties.GradleProperties
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.wrapper.Wrapper.DistributionType
import org.gradle.kotlin.dsl.ivy
import org.gradle.kotlin.dsl.replace
import org.gradle.kotlin.dsl.support.serviceOf

public class WrapperPlugin : Plugin<Any> {
    override fun apply(target: Any) {
        when (target) {
            is Gradle -> target.rootProject(::apply)

            is Settings -> target.gradle.rootProject(::apply)

            is Project -> {
                require(target === target.rootProject) {
                    "{${this::class.simpleName} MUST be applied in the root build script, not in ${target.path}."
                }
                apply(target)
            }

            else -> error("${this::class.simpleName} cannot be applied to target ${target::class.simpleName}, only init, settings, and root build scripts are supported.")
        }
    }

    private fun apply(project: Project) {
        val properties = project.serviceOf<GradleProperties>()
        val vendorName = properties.find(VENDOR_NAME_PROPERTY)?.toString()

        val vendorPrefix = vendorName?.let { "$it-" }
        val config = project.configurations.detachedConfiguration(*DistributionType.values().map { type ->
            project.dependencies.create("${vendorPrefix}gradle:$type:+@zip.sha256")
        }.toTypedArray())

        // TODO how to deal with unstable versions?
        // TODO how to deal with custom versions containing dashes?
        config.resolutionStrategy

        project.repositories.ivy(
            properties.find(REPOSITORY_URL_PROPERTY)?.toString() ?: "https://services.gradle.org/distributions/"
        ) {
            content { onlyForConfigurations(config.name) }
            metadataSources.artifact()
            patternLayout {
                artifact(
                    properties.find(REPOSITORY_PATTERN_PROPERTY)?.toString()
                        ?: "[organization]-[revision]-[artifact].[ext]"
                )
            }
        }

        project.tasks.replace("wrapper", Wrapper::class).apply {
            artifacts.apply {
                set(config.incoming.artifacts.resolvedArtifacts)
                disallowUnsafeRead()
                finalizeValueOnRead()
            }
        }
    }
}
