package org.jetbrains.compose.reload.utils

import org.gradle.testkit.runner.GradleRunner
import org.jetbrains.compose.reload.orchestration.ORCHESTRATION_SERVER_PORT_PROPERTY_KEY
import org.jetbrains.compose.reload.orchestration.startOrchestrationServer
import org.jetbrains.compose.reload.utils.HotReloadTestFixtureExtension.Companion.testFixtureKey
import org.junit.jupiter.api.TestTemplate
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.Extension
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import org.junit.jupiter.api.extension.TestTemplateInvocationContext
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
import org.junit.platform.commons.util.AnnotationUtils.findAnnotation
import java.nio.file.Files
import java.util.stream.Stream
import kotlin.streams.asStream

@TestTemplate
@ExtendWith(HotReloadTestInvocationContextProvider::class)
annotation class HotReloadTest

annotation class TestOnlyJvm

annotation class TestOnlyKmp

annotation class TestOnlyLatestVersions


internal class HotReloadTestInvocationContextProvider : TestTemplateInvocationContextProvider {
    override fun supportsTestTemplate(context: ExtensionContext): Boolean {
        if (context.testMethod.isEmpty) return false
        return findAnnotation(context.testMethod.get(), HotReloadTest::class.java) != null
    }

    override fun provideTestTemplateInvocationContexts(context: ExtensionContext): Stream<TestTemplateInvocationContext> {
        return TestedGradleVersion.entries.flatMap { testedGradleVersion ->
            TestedKotlinVersion.entries.flatMap { testedKotlinVersion ->
                TestedComposeVersion.entries.flatMap { testedComposeVersion ->
                    ProjectMode.entries.map { mode ->
                        HotReloadTestInvocationContext(
                            gradleVersion = testedGradleVersion,
                            kotlinVersion = testedKotlinVersion,
                            composeVersion = testedComposeVersion,
                            androidVersion = null,
                            projectMode = mode,
                        )
                    }
                }
            }
        }
            .filter { invocationContext ->
                invocationContext.projectMode == ProjectMode.Jvm ||
                        findAnnotation(context.testMethod, TestOnlyJvm::class.java).isEmpty
            }
            .filter { invocationContext ->
                invocationContext.projectMode == ProjectMode.Kmp ||
                        findAnnotation(context.testMethod, TestOnlyKmp::class.java).isEmpty
            }
            .filter { invocationContext ->
                (findAnnotation(context.testMethod, TestOnlyLatestVersions::class.java).isEmpty &&
                        System.getenv("TEST_ONLY_LATEST_VERSIONS") != "true") ||
                        invocationContext.gradleVersion == TestedGradleVersion.entries.last() &&
                        invocationContext.kotlinVersion == TestedKotlinVersion.entries.last() &&
                        invocationContext.composeVersion == TestedComposeVersion.entries.last() &&
                        (invocationContext.androidVersion == null || invocationContext.androidVersion == TestedAndroidVersion.entries.last())
            }

            .asSequence().asStream()
    }
}

data class HotReloadTestInvocationContext(
    val gradleVersion: TestedGradleVersion,
    val composeVersion: TestedComposeVersion,
    val kotlinVersion: TestedKotlinVersion,
    val androidVersion: TestedAndroidVersion?,
    val projectMode: ProjectMode
) : TestTemplateInvocationContext {
    override fun getDisplayName(invocationIndex: Int): String {
        return buildString {
            append("$projectMode,")
            append(" Gradle $gradleVersion,")
            append(" Kotlin $kotlinVersion,")
            append(" Compose $composeVersion,")
            if (androidVersion != null) {
                append(" Android $androidVersion")
            }

        }
    }

    override fun getAdditionalExtensions(): List<Extension> {
        return listOf(
            SimpleValueProvider(gradleVersion),
            SimpleValueProvider(kotlinVersion),
            SimpleValueProvider(composeVersion),
            HotReloadTestFixtureExtension(this),
        )
    }
}

private inline fun <reified T : Any> SimpleValueProvider(value: T): SimpleValueProvider<T> {
    return SimpleValueProvider(T::class.java, value)
}

private class SimpleValueProvider<T : Any>(
    private val type: Class<T>, private val value: T,
) : ParameterResolver {
    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
        return parameterContext.parameter.type == type
    }

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any {
        return value
    }
}

private class HotReloadTestFixtureExtension(
    private val context: HotReloadTestInvocationContext
) :
    ParameterResolver, BeforeEachCallback, AfterEachCallback {

    companion object {
        const val testFixtureKey = "hotReloadTestFixture"
    }

    override fun beforeEach(context: ExtensionContext) {
        context.projectMode = this.context.projectMode
        context.kotlinVersion = this.context.kotlinVersion
        context.gradleVersion = this.context.gradleVersion
        context.composeVersion = this.context.composeVersion
        context.androidVersion = this.context.androidVersion
        context.getOrCreateTestFixture()
    }

    override fun afterEach(context: ExtensionContext) {
        context.getHotReloadTestFixtureOrThrow().close()
    }

    override fun supportsParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Boolean {
        return parameterContext.parameter.type in listOf(
            HotReloadTestFixture::class.java,
            GradleRunner::class.java, ProjectDir::class.java
        )
    }

    override fun resolveParameter(parameterContext: ParameterContext, extensionContext: ExtensionContext): Any? {
        return when (parameterContext.parameter.type) {
            HotReloadTestFixture::class.java -> extensionContext.getHotReloadTestFixtureOrThrow()
            else -> throw IllegalArgumentException("Unknown type: ${parameterContext.parameter.type}")
        }
    }

    private fun ExtensionContext.getOrCreateTestFixture(): HotReloadTestFixture {
        return getStore(namespace).getOrComputeIfAbsent(
            testFixtureKey,
            { createTestFixture() },
            HotReloadTestFixture::class.java
        )
    }

    private fun ExtensionContext.createTestFixture(): HotReloadTestFixture {
        val projectDir = ProjectDir(Files.createTempDirectory("hot-reload-test"))
        val orchestrationServer = startOrchestrationServer()
        val gradleRunner = GradleRunner.create()
            .withProjectDir(projectDir.path.toFile())
            .withGradleVersion(context.gradleVersion.version.version)
            .forwardOutput()
            .addedArguments("-P$ORCHESTRATION_SERVER_PORT_PROPERTY_KEY=${orchestrationServer.port}")
            .addedArguments("-D$ORCHESTRATION_SERVER_PORT_PROPERTY_KEY=${orchestrationServer.port}")
            .addedArguments("--configuration-cache")
            .addedArguments("-Pcompose.reload.headless=true")
            .addedArguments("-i")
            .addedArguments("-s")

        return HotReloadTestFixture(
            testClassName = testClass.get().name,
            testMethodName = testMethod.get().name,
            projectDir = projectDir,
            gradleRunner = gradleRunner,
            orchestration = orchestrationServer,
            projectMode = context.projectMode,
        )
    }
}

internal fun ExtensionContext.getHotReloadTestFixtureOrThrow(): HotReloadTestFixture {
    return getStore(namespace).get(testFixtureKey, HotReloadTestFixture::class.java)
        ?: error("Missing '${HotReloadTestFixture::class.simpleName}'")
}
