package snyk.iac

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import org.junit.Before
import org.junit.Test
import java.awt.Container
import javax.swing.JButton

class IacSuggestionDescriptionPanelTest {

    private lateinit var project: Project
    private lateinit var cut: IacSuggestionDescriptionPanel
    private lateinit var issue: IacIssue

    @Before
    fun setUp() {
        issue = IacIssue(
            "IacTestIssue",
            "TestTitle",
            "TestSeverity",
            "IacTestIssuePublicString",
            "Test Documentation",
            123,
            "TestIssue",
            "TestImpact",
            "TestResolve",
            listOf("Test Reference 1", "Test reference 2"),
            listOf("Test Path 1", "Test Path 2")
        )
        project = mockk()
        every { project.basePath } returns ""
    }

    @Test
    fun `IacSuggestionDescriptionPanel should have ignore button`() {
        val expectedButtonText = "Ignore This Issue"

        cut = IacSuggestionDescriptionPanel(issue, project)

        val actualButton = getJButtonByText(cut, expectedButtonText)
        assertNotNull("Didn't find button with text $expectedButtonText", actualButton)
    }

    @Test
    fun `IacSuggestionDescriptionPanel ignore button should call IacIgnoreService on click`() {
        val expectedButtonText = "Ignore This Issue"

        cut = IacSuggestionDescriptionPanel(issue, project)

        val actualButton = getJButtonByText(cut, expectedButtonText)
        assertNotNull("Didn't find Ignore Button", actualButton)
        val listener = actualButton!!.actionListeners.first() as IgnoreButtonActionListener
        assertEquals(IgnoreButtonActionListener::class, listener::class)
        assertEquals(issue.id, listener.issueId)
    }

    private fun getJButtonByText(parent: Container, text: String): JButton? {
        val components = parent.components
        var found: JButton? = null
        for (component in components) {
            if (component is JButton && text == component.text) {
                found = component
            } else if (component is Container) {
                found = getJButtonByText(component, text)
            }
            if (found != null) {
                break
            }
        }
        return found
    }
}
