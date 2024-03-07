package io.snyk.plugin.extensions

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.snyk.plugin.getOssService
import io.snyk.plugin.getSnykCachedResults
import io.snyk.plugin.getSnykCliDownloaderService
import io.snyk.plugin.isCliInstalled
import io.snyk.plugin.pluginSettings
import io.snyk.plugin.removeDummyCliFile
import io.snyk.plugin.resetSettings
import io.snyk.plugin.services.download.LatestReleaseInfo
import io.snyk.plugin.services.download.SnykCliDownloaderService
import org.awaitility.Awaitility.await
import snyk.oss.OssResult
import snyk.oss.OssService
import snyk.trust.confirmScanningAndSetWorkspaceTrustedStateIfNeeded
import java.util.concurrent.TimeUnit

@Suppress("FunctionName")
class SnykControllerImplTest : LightPlatformTestCase() {

    private lateinit var ossServiceMock: OssService
    private lateinit var downloaderServiceMock: SnykCliDownloaderService

    override fun setUp() {
        super.setUp()
        unmockkAll()
        resetSettings(project)
        ossServiceMock = mockk(relaxed = true)
        project.replaceService(OssService::class.java, ossServiceMock, project)
        mockkStatic("io.snyk.plugin.UtilsKt")
        mockkStatic("snyk.trust.TrustedProjectsKt")
        downloaderServiceMock = spyk(SnykCliDownloaderService())
        every { downloaderServiceMock.requestLatestReleasesInformation() } returns LatestReleaseInfo(
            "http://testUrl",
            "testReleaseInfo",
            "testTag"
        )
        every { getSnykCliDownloaderService() } returns downloaderServiceMock
        every { downloaderServiceMock.isFourDaysPassedSinceLastCheck() } returns false
        every { confirmScanningAndSetWorkspaceTrustedStateIfNeeded(any()) } returns true
    }

    override fun tearDown() {
        unmockkAll()
        resetSettings(project)
        removeDummyCliFile()
        super.tearDown()
    }

    fun testControllerCanTriggerScan() {
        mockkStatic("io.snyk.plugin.UtilsKt")
        every { isCliInstalled() } returns true
        val fakeResult = OssResult(emptyList())
        every { getOssService(project)?.scan() } returns fakeResult

        val settings = pluginSettings()
        settings.ossScanEnable = true
        settings.snykCodeSecurityIssuesScanEnable = false
        settings.snykCodeQualityIssuesScanEnable = false
        settings.iacScanEnabled = false
        settings.containerScanEnabled = false

        getSnykCachedResults(project)?.currentContainerResult = null

        val controller = SnykControllerImpl(project)
        controller.scan()

        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        await().atMost(2, TimeUnit.SECONDS).until {
            getSnykCachedResults(project)?.currentOssResults != null
        }
    }
}