package io.snyk.plugin.ui.toolwindow

import UIComponentFinder
import com.intellij.mock.MockPsiFile
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.impl.ApplicationInfoImpl
import com.intellij.openapi.components.service
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.util.ui.tree.TreeUtil
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import io.snyk.plugin.Severity
import io.snyk.plugin.cli.ConsoleCommandRunner
import io.snyk.plugin.getCliFile
import io.snyk.plugin.getContainerService
import io.snyk.plugin.getIacService
import io.snyk.plugin.getKubernetesImageCache
import io.snyk.plugin.isCliInstalled
import io.snyk.plugin.isOssRunning
import io.snyk.plugin.pluginSettings
import io.snyk.plugin.removeDummyCliFile
import io.snyk.plugin.resetSettings
import io.snyk.plugin.services.SnykTaskQueueService
import io.snyk.plugin.setupDummyCliFile
import io.snyk.plugin.ui.SnykBalloonNotificationHelper
import io.snyk.plugin.ui.actions.SnykTreeMediumSeverityFilterAction
import org.junit.Test
import snyk.common.SnykError
import snyk.container.ContainerIssue
import snyk.container.ContainerIssuesForImage
import snyk.container.ContainerResult
import snyk.container.ContainerService
import snyk.container.Docker
import snyk.container.KubernetesWorkloadImage
import snyk.container.ui.BaseImageRemediationDetailPanel
import snyk.container.ui.ContainerImageTreeNode
import snyk.iac.IacIssue
import snyk.iac.IacResult
import snyk.iac.IacSuggestionDescriptionPanel
import snyk.iac.IgnoreButtonActionListener
import snyk.iac.ui.toolwindow.IacFileTreeNode
import snyk.iac.ui.toolwindow.IacIssueTreeNode
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JTextArea
import javax.swing.tree.TreeNode

@Suppress("FunctionName")
class SnykToolWindowPanelIntegTest : HeavyPlatformTestCase() {

    private val iacGoofJson = getResourceAsString("iac-test-results/infrastructure-as-code-goof.json")
    private val containerResultWithRemediationJson =
        getResourceAsString("container-test-results/nginx-with-remediation.json")

    private fun getResourceAsString(resourceName: String): String = javaClass.classLoader
        .getResource(resourceName)!!.readText(Charsets.UTF_8)

    override fun setUp() {
        super.setUp()
        unmockkAll()
        resetSettings(project)
        setupDummyCliFile()
        // restore modified Registry value
        isIacEnabledRegistryValue.setValue(isIacEnabledDefaultValue)
        isContainerEnabledRegistryValue.setValue(isContainerEnabledDefaultValue)
    }

    override fun tearDown() {
        unmockkAll()
        resetSettings(project)
        removeDummyCliFile()
        // restore modified Registry value
        isIacEnabledRegistryValue.setValue(isIacEnabledDefaultValue)
        isContainerEnabledRegistryValue.setValue(isContainerEnabledDefaultValue)
        super.tearDown()
    }

    private val isIacEnabledRegistryValue = Registry.get("snyk.preview.iac.enabled")
    private val isIacEnabledDefaultValue: Boolean by lazy { isIacEnabledRegistryValue.asBoolean() }

    private val isContainerEnabledRegistryValue = Registry.get("snyk.preview.container.enabled")
    private val isContainerEnabledDefaultValue: Boolean by lazy { isContainerEnabledRegistryValue.asBoolean() }

    private fun setUpIacTest() {
        val settings = pluginSettings()
        settings.ossScanEnable = false
        settings.snykCodeSecurityIssuesScanEnable = false
        settings.snykCodeQualityIssuesScanEnable = false
        settings.iacScanEnabled = true
        settings.containerScanEnabled = false

        isIacEnabledRegistryValue.setValue(true)
    }

    private fun setUpContainerTest() {
        val settings = pluginSettings()
        settings.ossScanEnable = false
        settings.snykCodeSecurityIssuesScanEnable = false
        settings.snykCodeQualityIssuesScanEnable = false
        settings.iacScanEnabled = false
        settings.containerScanEnabled = true

        isContainerEnabledRegistryValue.setValue(true)
    }

    private fun prepareTreeWithFakeIacResults() {
        setUpIacTest()

        val mockRunner = mockk<ConsoleCommandRunner>()
        every {
            mockRunner.execute(
                listOf(getCliFile().absolutePath, "iac", "test", "--json"),
                project.basePath!!,
                project = project
            )
        } returns (iacGoofJson)

        getIacService(project)?.setConsoleCommandRunner(mockRunner)

        project.service<SnykTaskQueueService>().scan()
    }

    @Test
    fun `test should not display error when no OSS supported file found`() {
        mockkObject(SnykBalloonNotificationHelper)

        val toolWindowPanel = project.service<SnykToolWindowPanel>()

        val snykError = SnykError("Could not detect supported target files in", project.basePath.toString())
        val snykErrorControl = SnykError("control", project.basePath.toString())

        toolWindowPanel.snykScanListener.scanningOssError(snykErrorControl)
        toolWindowPanel.snykScanListener.scanningOssError(snykError)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        verify(exactly = 1, timeout = 2000) {
            SnykBalloonNotificationHelper.showError(snykErrorControl.message, project)
            SnykBalloonNotificationHelper.showInfo(snykError.message, project)
        }
        assertTrue(toolWindowPanel.currentOssError == null)
        assertTrue(toolWindowPanel.currentOssResults == null)
        assertEquals(SnykToolWindowPanel.OSS_ROOT_TEXT, toolWindowPanel.getRootOssIssuesTreeNode().userObject)
    }

    @Test
    fun `test should display '(error)' in OSS root tree node when result is empty and error occurs`() {
        val toolWindowPanel = project.service<SnykToolWindowPanel>()
        val snykError = SnykError("an error", project.basePath.toString())
        toolWindowPanel.snykScanListener.scanningOssError(snykError)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        assertTrue(toolWindowPanel.currentOssError == snykError)
        assertTrue(toolWindowPanel.currentOssResults == null)
        assertEquals(
            SnykToolWindowPanel.OSS_ROOT_TEXT + " (error)",
            toolWindowPanel.getRootOssIssuesTreeNode().userObject
        )
    }

    @Test
    fun `test should display 'scanning' in OSS root tree node when it is scanning`() {
        mockkStatic("io.snyk.plugin.UtilsKt")
        every { isOssRunning(project) } returns true
        val toolWindowPanel = project.service<SnykToolWindowPanel>()
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

        toolWindowPanel.updateTreeRootNodesPresentation(null, 0, 0, 0)
        assertTrue(toolWindowPanel.currentOssResults == null)
        assertEquals(
            SnykToolWindowPanel.OSS_ROOT_TEXT + " (scanning...)",
            toolWindowPanel.getRootOssIssuesTreeNode().userObject
        )
    }

    @Test
    fun testSeverityFilterForIacResult() {
        // pre-test setup
        prepareTreeWithFakeIacResults()

        // actual test run
        val toolWindowPanel = project.service<SnykToolWindowPanel>()

        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())

        val rootIacIssuesTreeNode = toolWindowPanel.getRootIacIssuesTreeNode()
        fun isMediumSeverityShown(): Boolean = rootIacIssuesTreeNode.children().asSequence()
            .flatMap { (it as TreeNode).children().asSequence() }
            .any {
                it is IacIssueTreeNode &&
                    it.userObject is IacIssue &&
                    (it.userObject as IacIssue).severity == Severity.MEDIUM
            }

        assertTrue("Medium severity IaC results should be shown by default", isMediumSeverityShown())

        val mediumSeverityFilterAction =
            ActionManager.getInstance().getAction("io.snyk.plugin.ui.actions.SnykTreeMediumSeverityFilterAction")
                as SnykTreeMediumSeverityFilterAction
        mediumSeverityFilterAction.setSelected(TestActionEvent(), false)

        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())

        assertFalse("Medium severity IaC results should NOT be shown after filtering", isMediumSeverityShown())
    }

    @Test
    fun testIacErrorShown() {
        // pre-test setup
        setUpIacTest()

        // mock IaC results
        val iacError = SnykError("fake error", "fake path")
        val iacResultWithError = IacResult(null, iacError)

        mockkStatic("io.snyk.plugin.UtilsKt")
        every { isCliInstalled() } returns true
        every { getIacService(project)?.scan() } returns iacResultWithError

        // actual test run
        project.service<SnykTaskQueueService>().scan()

        val toolWindowPanel = project.service<SnykToolWindowPanel>()
        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())

        assertEquals(iacError, toolWindowPanel.currentIacError)

        TreeUtil.selectNode(toolWindowPanel.getTree(), toolWindowPanel.getRootIacIssuesTreeNode())
        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())

        val descriptionComponents = toolWindowPanel.getDescriptionPanel().components.toList()
        val errorPanel = descriptionComponents.find { it is SnykErrorPanel } as SnykErrorPanel?

        assertNotNull(errorPanel)

        val errorMessageTextArea =
            UIComponentFinder.getComponentByName(errorPanel!!, JTextArea::class, "errorMessageTextArea")
        val pathTextArea = UIComponentFinder.getComponentByName(errorPanel, JTextArea::class, "pathTextArea")

        assertTrue(errorMessageTextArea?.text == iacError.message)
        assertTrue(pathTextArea?.text == iacError.path)
    }

    fun test_WhenIacIssueIgnored_ThenItMarkedIgnored_AndButtonRemainsDisabled() {
        // pre-test setup
        prepareTreeWithFakeIacResults()

        val toolWindowPanel = project.service<SnykToolWindowPanel>()
        val tree = toolWindowPanel.getTree()
        PlatformTestUtil.waitWhileBusy(tree)

        // select first IaC issue and ignore it
        val rootIacIssuesTreeNode = toolWindowPanel.getRootIacIssuesTreeNode()
        val firstIaCFileNode = rootIacIssuesTreeNode.firstChild as? IacFileTreeNode
        val firstIacIssueNode = firstIaCFileNode?.firstChild as? IacIssueTreeNode
            ?: throw IllegalStateException()
        TreeUtil.selectNode(tree, firstIacIssueNode)

        // hack to avoid "File accessed outside allowed roots" check in tests
        // needed due to com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.assertAccessInTests
        val prev_isInStressTest = ApplicationInfoImpl.isInStressTest()
        ApplicationInfoImpl.setInStressTest(true)
        try {
            PlatformTestUtil.waitWhileBusy(tree)
        } finally {
            ApplicationInfoImpl.setInStressTest(prev_isInStressTest)
        }

        fun iacDescriptionPanel() =
            UIComponentFinder.getComponentByName(
                toolWindowPanel.getDescriptionPanel(),
                IacSuggestionDescriptionPanel::class,
                "IacSuggestionDescriptionPanel"
            ) ?: throw IllegalStateException()

        val ignoreButton = UIComponentFinder.getComponentByName(iacDescriptionPanel(), JButton::class, "ignoreButton")
            ?: throw IllegalStateException()

        assertFalse(
            "Issue should NOT be ignored by default",
            (firstIacIssueNode.userObject as IacIssue).ignored
        )
        assertTrue(
            "Ignore Button should be enabled by default",
            ignoreButton.isEnabled && ignoreButton.text != IgnoreButtonActionListener.IGNORED_ISSUE_BUTTON_TEXT
        )

        ignoreButton.doClick()

        // check final state
        assertTrue(
            "Issue should be marked as ignored after ignoring",
            (firstIacIssueNode.userObject as IacIssue).ignored
        )
        assertTrue(
            "Ignore Button should be disabled for ignored issue",
            !ignoreButton.isEnabled && ignoreButton.text == IgnoreButtonActionListener.IGNORED_ISSUE_BUTTON_TEXT
        )
        PlatformTestUtil.waitWhileBusy(tree)
        TreeUtil.selectNode(tree, firstIacIssueNode.nextNode)
        PlatformTestUtil.waitWhileBusy(tree)
        TreeUtil.selectNode(tree, firstIacIssueNode)
        PlatformTestUtil.waitWhileBusy(tree)
        assertTrue(
            "Ignore Button should remain disabled for ignored issue",
            !ignoreButton.isEnabled && ignoreButton.text == IgnoreButtonActionListener.IGNORED_ISSUE_BUTTON_TEXT
        )
    }

    @Test
    fun `test all root nodes are shown`() {
        setUpIacTest()
        setUpContainerTest()

        val toolWindowPanel = project.service<SnykToolWindowPanel>()
        val tree = toolWindowPanel.getTree()
        PlatformTestUtil.waitWhileBusy(tree)

        val rootNode = toolWindowPanel.getRootNode()
        setOf(
            SnykToolWindowPanel.OSS_ROOT_TEXT,
            SnykToolWindowPanel.SNYKCODE_SECURITY_ISSUES_ROOT_TEXT,
            SnykToolWindowPanel.SNYKCODE_QUALITY_ISSUES_ROOT_TEXT,
            SnykToolWindowPanel.IAC_ROOT_TEXT,
            SnykToolWindowPanel.CONTAINER_ROOT_TEXT
        ).forEach {
            assertTrue(
                "Root node for [$it] not found",
                TreeUtil.findNodeWithObject(rootNode, it) != null
            )
        }
    }

    @Test
    fun `test container error shown`() {
        // pre-test setup
        setUpContainerTest()

        // mock Container results
        val containerError = SnykError("fake error", "fake path")
        val containerResultWithError = ContainerResult(null, containerError)

        mockkStatic("io.snyk.plugin.UtilsKt")
        every { isCliInstalled() } returns true
        every { getContainerService(project)?.scan() } returns containerResultWithError

        // actual test run
        project.service<SnykTaskQueueService>().scan()

        val toolWindowPanel = project.service<SnykToolWindowPanel>()
        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())

        assertEquals(containerError, toolWindowPanel.currentContainerError)

        TreeUtil.selectNode(toolWindowPanel.getTree(), toolWindowPanel.getRootContainerIssuesTreeNode())
        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())

        val descriptionComponents = toolWindowPanel.getDescriptionPanel().components.toList()
        val errorPanel = descriptionComponents.find { it is SnykErrorPanel } as? SnykErrorPanel

        assertNotNull(errorPanel)

        val errorMessageTextArea =
            UIComponentFinder.getComponentByName(errorPanel!!, JTextArea::class, "errorMessageTextArea")
        val pathTextArea = UIComponentFinder.getComponentByName(errorPanel, JTextArea::class, "pathTextArea")

        assertTrue(errorMessageTextArea?.text == containerError.message)
        assertTrue(pathTextArea?.text == containerError.path)
    }

    @Test
    fun `test container image nodes with description shown`() {
        // pre-test setup
        setUpContainerTest()
        // mock Container results
        val containerResult = ContainerResult(
            listOf(
                ContainerIssuesForImage(
                    listOf(ContainerIssue(
                        id = "fakeId1",
                        title = "fakeTitle1",
                        description = "fakeDescription1",
                        severity = "low",
                        from = emptyList(),
                        packageManager = "fakePackageManager1"
                    )),
                    "fake project name",
                    Docker(),
                    null,
                    "fake-image-name1"
                ),
                ContainerIssuesForImage(
                    listOf(ContainerIssue(
                        id = "fakeId2",
                        title = "fakeTitle2",
                        description = "fakeDescription2",
                        severity = "low",
                        from = emptyList(),
                        packageManager = "fakePackageManager2"
                    )),
                    "fake project name",
                    Docker(),
                    null,
                    "fake-image-name2"
                )
            ),
            null
        )
        mockkStatic("io.snyk.plugin.UtilsKt")
        every { isCliInstalled() } returns true
        every { getContainerService(project)?.scan() } returns containerResult

        // actual test run
        project.service<SnykTaskQueueService>().scan()
        val toolWindowPanel = project.service<SnykToolWindowPanel>()
        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())

        // Assertions
        assertEquals(containerResult, toolWindowPanel.currentContainerResult)

        val rootContainerNode = toolWindowPanel.getRootContainerIssuesTreeNode()
        assertEquals("2 images with issues should be found", 2, rootContainerNode.childCount)
        assertEquals(
            "`fake-image-name1` should be found",
            "fake-image-name1",
            ((rootContainerNode.firstChild as ContainerImageTreeNode).userObject as ContainerIssuesForImage).imageName
        )
        assertEquals(
            "`fake-image-name2` should be found",
            "fake-image-name2",
            ((rootContainerNode.lastChild as ContainerImageTreeNode).userObject as ContainerIssuesForImage).imageName
        )

        TreeUtil.selectNode(toolWindowPanel.getTree(), rootContainerNode.firstChild)
        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())
        val baseImageRemediationDetailPanel = UIComponentFinder.getComponentByName(
            toolWindowPanel.getDescriptionPanel(),
            BaseImageRemediationDetailPanel::class,
            "BaseImageRemediationDetailPanel"
        )
        assertNotNull(baseImageRemediationDetailPanel)
    }

    @Test
    fun `test container image nodes with remediation description shown`() {
        // pre-test setup
        setUpContainerTest()
        // mock Container results
        mockkStatic("io.snyk.plugin.UtilsKt")
        every { isCliInstalled() } returns true
        every { getKubernetesImageCache(project)?.getKubernetesWorkloadImages() } returns setOf(
            KubernetesWorkloadImage("ignored_image_name", MockPsiFile(PsiManager.getInstance(project)))
        )
        val containerService = ContainerService(project)
        val mockkRunner = mockk<ConsoleCommandRunner>()
        every { mockkRunner.execute(any(), any(), any(), project) } returns containerResultWithRemediationJson
        containerService.setConsoleCommandRunner(mockkRunner)
        every { getContainerService(project)?.scan() } returns containerService.scan()

        // actual test run
        project.service<SnykTaskQueueService>().scan()
        val toolWindowPanel = project.service<SnykToolWindowPanel>()
        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())
        val rootContainerNode = toolWindowPanel.getRootContainerIssuesTreeNode()
        TreeUtil.selectNode(toolWindowPanel.getTree(), rootContainerNode.firstChild)
        PlatformTestUtil.waitWhileBusy(toolWindowPanel.getTree())

        // Assertions
        val currentImageValueLabel = UIComponentFinder.getComponentByName(
            toolWindowPanel.getDescriptionPanel(),
            JLabel::class,
            BaseImageRemediationDetailPanel.CURRENT_IMAGE
        )
        assertNotNull(currentImageValueLabel)
        assertEquals("current image incorrect", "nginx:1.16.0", currentImageValueLabel?.text)

        val minorUpgradeValueLabel = UIComponentFinder.getComponentByName(
            toolWindowPanel.getDescriptionPanel(),
            JLabel::class,
            BaseImageRemediationDetailPanel.MINOR_UPGRADES
        )
        assertNotNull(minorUpgradeValueLabel)
        assertEquals("minor upgrades incorrect", "nginx:1.20.2", minorUpgradeValueLabel?.text)

        val alternativeUpgradeValueLabel = UIComponentFinder.getComponentByName(
            toolWindowPanel.getDescriptionPanel(),
            JLabel::class,
            BaseImageRemediationDetailPanel.ALTERNATIVE_UPGRADES
        )
        assertNotNull(alternativeUpgradeValueLabel)
        assertEquals("alternative upgrades incorrect", "nginx:1-perl", alternativeUpgradeValueLabel?.text)
    }
}
