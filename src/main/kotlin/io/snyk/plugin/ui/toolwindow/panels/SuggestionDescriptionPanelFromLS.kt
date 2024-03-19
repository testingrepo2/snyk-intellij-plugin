package io.snyk.plugin.ui.toolwindow.panels

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.uiDesigner.core.GridConstraints
import com.intellij.uiDesigner.core.GridLayoutManager
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.UIUtil
import io.snyk.plugin.getDocument
import io.snyk.plugin.navigateToSource
import io.snyk.plugin.snykcode.core.SnykCodeFile
import io.snyk.plugin.toVirtualFile
import io.snyk.plugin.ui.DescriptionHeaderPanel
import io.snyk.plugin.ui.baseGridConstraintsAnchorWest
import io.snyk.plugin.ui.descriptionHeaderPanel
import io.snyk.plugin.ui.panelGridConstraints
import jcef.JsDialogHandler
import snyk.common.lsp.DataFlow
import snyk.common.lsp.LanguageServerWrapper
import snyk.common.lsp.ScanIssue
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.ScrollPaneConstants
import javax.swing.event.HyperlinkEvent
import kotlin.math.max


class SuggestionDescriptionPanelFromLS(
    snykCodeFile: SnykCodeFile,
    private val issue: ScanIssue
) : IssueDescriptionPanelBase(title = getIssueTitle(issue), severity = issue.getSeverityAsEnum()) {
    val project = snykCodeFile.project

    init {
        createUI()
    }

    override fun secondRowTitlePanel(): DescriptionHeaderPanel = descriptionHeaderPanel(
        issueNaming = if (issue.additionalData.isSecurityType) "Vulnerability" else "Quality Issue",
        cwes = issue.additionalData.cwe ?: emptyList()
    )

    override fun createMainBodyPanel(): Pair<JPanel, Int> {
        val lastRowToAddSpacer = 5
        val panel = JPanel(
            GridLayoutManager(lastRowToAddSpacer + 1, 1, Insets(0, 10, 20, 10), -1, 20)
        ).apply {
            this.add(overviewPanel(), panelGridConstraints(2))

            dataFlowPanel()?.let { this.add(it, panelGridConstraints(3)) }

            this.add(fixExamplesPanel(), panelGridConstraints(4))
        }
        return Pair(panel, lastRowToAddSpacer)
    }

    private fun overviewPanel(): JComponent {
        // TODO: feature flag
        val panel = JPanel()
        panel.layout = GridLayoutManager(2, 1, Insets(0, 0, 0, 0), -1, -1)
        // TODO: check feature flag and if JCEF supported
        if (!JBCefApp.isSupported()) {
            // TODO: error state
            val label = JLabel("<html> Error </html>").apply {
                this.isOpaque = false
                this.background = UIUtil.getPanelBackground()
                this.font = io.snyk.plugin.ui.getFont(-1, 14, panel.font)
                this.preferredSize = Dimension() // this is the key part for shrink/grow.
            }
            panel.add(label, panelGridConstraints(1, indent = 1))
        }

        val jbCefBrowser = JBCefBrowser()
        jbCefBrowser.jbCefClient
            .addJSDialogHandler(
                JsDialogHandler(),
                jbCefBrowser.cefBrowser
            )

        panel.add(jbCefBrowser.component, panelGridConstraints(1, indent = 1))

        val sendScanQuery = JBCefJSQuery.create(jbCefBrowser as JBCefBrowserBase)
        val handler:Function<JBCefJSQuery.Response> = {
            LanguageServerWrapper.getInstance().sendScanCommand()
            return null
        }
        sendScanQuery.addHandler(handler)
        browser.getCefBrowser().executeJavaScript( // 4
            "window.openLink = function(link) {" +
                openLinkQuery.inject("link") + // 5
                "};",
            browser.getCefBrowser().getURL(), 0
        );

        browser.getCefBrowser().executeJavaScript( // 6
            """
    document.addEventListener('click', function (e) {
      const link = e.target.closest('a').href;
      if (link) {
        window.openLink(link);
      }
    });""",
            browser.getCefBrowser().getURL(), 0
        );

        var severityIcon = "/Users/teodorasandu/Documents/repos/ide/snyk-intellij-plugin/src/main/resources/icons/severity_critical_16.svg"
        jbCefBrowser.loadHTML("<!--\n" +
            "  ~ © 2024 Snyk Limited\n" +
            "  ~\n" +
            "  ~ Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
            "  ~ you may not use this file except in compliance with the License.\n" +
            "  ~ You may obtain a copy of the License at\n" +
            "  ~\n" +
            "  ~     http://www.apache.org/licenses/LICENSE-2.0\n" +
            "  ~\n" +
            "  ~ Unless required by applicable law or agreed to in writing, software\n" +
            "  ~ distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
            "  ~ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
            "  ~ See the License for the specific language governing permissions and\n" +
            "  ~ limitations under the License.\n" +
            "  -->\n" +
            "\n" +
            "<!DOCTYPE html>\n" +
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "  <meta charset=\"UTF-8\">\n" +
            "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "  <style>\n" +
            "    .suggestion {\n" +
            "      position: relative;\n" +
            "      display: flex;\n" +
            "      flex-direction: column;\n" +
            "      width: 100%;\n" +
            "      height: 100%;\n" +
            "    }\n" +
            "\n" +
            "    .suggestion .suggestion-text {\n" +
            "      padding: 0.4rem 0;\n" +
            "      margin-bottom: 0.8rem;\n" +
            "      font-size: 1.8rem;\n" +
            "      font-weight: 500;\n" +
            "      line-height: 2.4rem;\n" +
            "    }\n" +
            "\n" +
            "    .severity {\n" +
            "      display: flex;\n" +
            "      flex-direction: column;\n" +
            "      flex-grow: 0;\n" +
            "      float: left;\n" +
            "      margin: 0 1rem 0 0;\n" +
            "      text-align: center;\n" +
            "    }\n" +
            "\n" +
            "    .severity .icon {\n" +
            "      width: 32px;\n" +
            "      height: 32px;\n" +
            "    }\n" +
            "\n" +
            "    .icon {\n" +
            "      vertical-align: middle;\n" +
            "      width: 16px;\n" +
            "      height: 16px;\n" +
            "    }\n" +
            "\n" +
            "    .identifiers {\n" +
            "      font-size: 1.3rem;\n" +
            "      line-height: 2rem;\n" +
            "    }\n" +
            "\n" +
            "    .vscode-dark .light-only {\n" +
            "      display: none;\n" +
            "    }\n" +
            "\n" +
            "    .vscode-light .dark-only {\n" +
            "      display: none;\n" +
            "    }\n" +
            "\n" +
            "    .learn {\n" +
            "      opacity: 0;\n" +
            "      height: 0;\n" +
            "      margin-top: 0;\n" +
            "      font-size: 1.3rem;\n" +
            "    }\n" +
            "\n" +
            "    .learn.show {\n" +
            "      margin-top: 6px;\n" +
            "      opacity: 1;\n" +
            "      height: auto;\n" +
            "      transition-duration: 500ms;\n" +
            "      transition-property: height, opacity, margin-top;\n" +
            "    }\n" +
            "\n" +
            "    .learn--link {\n" +
            "      margin-left: 3px;\n" +
            "    }\n" +
            "\n" +
            "    .learn__code .learn--link {\n" +
            "    }\n" +
            "\n" +
            "  </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "<div class=\"suggestion\">\n" +
            "  <section class=\"suggestion--header\">\n" +
            "    <div class=\"severity\">\n" +
            "      <img id=\"severity-icon\" src=\"${severityIcon}\" class=\"icon\"/>\n" +
            "    </div>\n" +
            "    <div class=\"suggestion-text\">Issue title</div>\n" +
//            "    <div class=\"learn\" id=\"learn\">\n" +
//            "      <img class=\"icon\" src=\"${learnIcon}\" alt=\"learnIcon\" />\n" +
//            "      ${learnLink}\n" +
//            "    </div>\n" +
            "  </section>\n" +
            "  <section class=\"delimiter-top summary\">\n" +
            "    <button> Send scan </button>\n" +
            "    <h2 style='background-color: blue'>Detailed paths</h2>\n" +
//            "    <div class=\"detailed-paths\">${detailedPaths}</div>\n" +
            "  </section>\n" +
            "  <section class=\"delimiter-top\">\n" +
            "    <div id=\"overview\" class=\"vulnerability-overview\">${getOverviewText()}</div>\n" +
            "  </section>\n" +
            "</div>\n" +
            "<script>\n" +
            "  if (document.getElementById(\"learn--link\")) {\n" +
            "    document.getElementById(\"learn\").className = \"learn show\"\n" +
            "  }\n" +
            "</script>\n" +
            "</body>\n" +
            "</html>\n", jbCefBrowser.getCefBrowser().getURL())

        return panel
    }

    private fun codeLine(content: String): JTextArea {
        val component = JTextArea(content)
        component.font = io.snyk.plugin.ui.getFont(-1, 14, component.font)
        component.isEditable = false
        return component
    }

    private fun defaultFontLabel(labelText: String, bold: Boolean = false): JLabel {
        return JLabel().apply {
            val titleLabelFont: Font? = io.snyk.plugin.ui.getFont(if (bold) Font.BOLD else -1, 14, font)
            titleLabelFont?.let { font = it }
            text = labelText
        }
    }

    private fun linkLabel(
        beforeLinkText: String = "",
        linkText: String,
        afterLinkText: String = "",
        toolTipText: String,
        customFont: Font? = null,
        onClick: (HyperlinkEvent) -> Unit
    ): HyperlinkLabel {
        return HyperlinkLabel().apply {
            this.setTextWithHyperlink("$beforeLinkText<hyperlink>$linkText</hyperlink>$afterLinkText")
            this.toolTipText = toolTipText
            this.font = io.snyk.plugin.ui.getFont(-1, 14, customFont ?: font)
            addHyperlinkListener {
                onClick.invoke(it)
            }
        }
    }

    private fun dataFlowPanel(): JPanel? {
        val dataFlow = issue.additionalData.dataFlow

        val panel = JPanel()
        panel.layout = GridLayoutManager(1 + dataFlow.size, 1, Insets(0, 0, 0, 0), -1, 5)

        panel.add(
            defaultFontLabel("Data Flow - ${dataFlow.size} step${if (dataFlow.size > 1) "s" else ""}", true),
            baseGridConstraintsAnchorWest(0)
        )

        panel.add(
            stepsPanel(dataFlow),
            baseGridConstraintsAnchorWest(1)
        )

        return panel
    }

    private fun stepsPanel(dataflow: List<DataFlow>): JPanel {
        val panel = JPanel()
        panel.layout = GridLayoutManager(dataflow.size, 1, Insets(0, 0, 0, 0), 0, 0)
        panel.background = UIUtil.getTextFieldBackground()

        val maxFilenameLength = dataflow.asSequence()
            .filter { it.filePath.isNotEmpty() }
            .map { it.filePath.substringAfterLast('/', "").length }
            .maxOrNull() ?: 0

        val allStepPanels = mutableListOf<JPanel>()
        dataflow.forEach { flow ->
            val stepPanel = stepPanel(
                index = flow.position,
                flow = flow,
                maxFilenameLength = max(flow.filePath.toVirtualFile().name.length, maxFilenameLength),
                allStepPanels = allStepPanels
            )

            panel.add(
                stepPanel,
                baseGridConstraintsAnchorWest(
                    row = flow.position,
                    fill = GridConstraints.FILL_BOTH,
                    indent = 0
                )
            )
            allStepPanels.add(stepPanel)
        }

        return panel
    }

    private fun stepPanel(
        index: Int,
        flow: DataFlow,
        maxFilenameLength: Int,
        allStepPanels: MutableList<JPanel>
    ): JPanel {
        val stepPanel = JPanel()
        stepPanel.layout = GridLayoutManager(1, 3, Insets(0, 0, 4, 0), 0, 0)
        stepPanel.background = UIUtil.getTextFieldBackground()

        val paddedStepNumber = (index + 1).toString().padStart(2, ' ')

        val virtualFile = flow.filePath.toVirtualFile()
        val fileName = virtualFile.name

        val lineNumber = flow.flowRange.start.line + 1
        val positionLinkText = "$fileName:$lineNumber".padEnd(maxFilenameLength + 5, ' ')

        val positionLabel = linkLabel(
            beforeLinkText = "$paddedStepNumber  ",
            linkText = positionLinkText,
            afterLinkText = " |",
            toolTipText = "Click to show in the Editor",
            customFont = JTextArea().font
        ) {
            if (!virtualFile.isValid) return@linkLabel

            val document = virtualFile.getDocument()
            val startLineStartOffset = document?.getLineStartOffset(flow.flowRange.start.line) ?: 0
            val startOffset = startLineStartOffset + (flow.flowRange.start.character)
            val endLineStartOffset = document?.getLineStartOffset(flow.flowRange.end.line) ?: 0
            val endOffset = endLineStartOffset + flow.flowRange.end.character - 1

            navigateToSource(project, virtualFile, startOffset, endOffset)

            allStepPanels.forEach {
                it.background = UIUtil.getTextFieldBackground()
            }
            stepPanel.background = UIUtil.getTableSelectionBackground(false)
        }
        stepPanel.add(positionLabel, baseGridConstraintsAnchorWest(0, indent = 1))

        val codeLine = codeLine(flow.content)
        codeLine.isOpaque = false
        stepPanel.add(
            codeLine,
            baseGridConstraintsAnchorWest(
                row = 0,
                // is needed to avoid center alignment when outer panel is filling horizontal space
                hSizePolicy = GridConstraints.SIZEPOLICY_CAN_GROW,
                column = 1
            )
        )

        return stepPanel
    }

    private fun fixExamplesPanel(): JPanel {
        val fixes = issue.additionalData.exampleCommitFixes
        val examplesCount = fixes.size.coerceAtMost(3)

        val panel = JPanel()
        panel.layout = GridLayoutManager(3, 1, Insets(0, 0, 0, 0), -1, 5)

        panel.add(
            defaultFontLabel("External example fixes", true),
            baseGridConstraintsAnchorWest(0)
        )

        val labelText =
            if (examplesCount == 0) {
                "No example fixes available."
            } else {
                "This issue was fixed by ${issue.additionalData.repoDatasetSize} projects. Here are $examplesCount example fixes."
            }
        panel.add(
            defaultFontLabel(labelText),
            baseGridConstraintsAnchorWest(1)
        )

        if (examplesCount == 0) return panel

        val tabbedPane = JBTabbedPane()
        // tabbedPane.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT // tabs in one row
        tabbedPane.tabComponentInsets = JBInsets.create(0, 0) // no inner borders for tab content
        tabbedPane.font = io.snyk.plugin.ui.getFont(-1, 14, tabbedPane.font)

        val tabbedPanel = JPanel()
        tabbedPanel.layout = GridLayoutManager(1, 1, Insets(10, 0, 0, 0), -1, -1)
        tabbedPanel.add(tabbedPane, panelGridConstraints(0))

        panel.add(tabbedPanel, panelGridConstraints(2, indent = 1))

        val maxRowCount = fixes.take(examplesCount).maxOfOrNull { it.lines.size } ?: 0
        fixes.take(examplesCount).forEach { exampleCommitFix ->
            val shortURL = exampleCommitFix.commitURL
                .removePrefix("https://")
                .replace(Regex("/commit/.*"), "")

            val tabTitle = shortURL.removePrefix("github.com/").let {
                if (it.length > 50) it.take(50) + "..." else it
            }

            val icon = if (shortURL.startsWith("github.com")) AllIcons.Vcs.Vendors.Github else null

            tabbedPane.addTab(
                tabTitle,
                icon,
                diffPanel(exampleCommitFix, maxRowCount),
                shortURL
            )
        }

        return panel
    }

    private fun diffPanel(exampleCommitFix: snyk.common.lsp.ExampleCommitFix, rowCount: Int): JComponent {
        fun shift(colorComponent: Int, d: Double): Int {
            val n = (colorComponent * d).toInt()
            return n.coerceIn(0, 255)
        }

        val baseColor = UIUtil.getTextFieldBackground()
        val addedColor = Color(
            shift(baseColor.red, 0.75),
            baseColor.green,
            shift(baseColor.blue, 0.75)
        )
        val removedColor = Color(
            shift(baseColor.red, 1.25),
            shift(baseColor.green, 0.85),
            shift(baseColor.blue, 0.85)
        )

        val panel = JPanel()
        panel.layout = GridLayoutManager(rowCount, 1, Insets(0, 0, 0, 0), -1, 0)
        panel.background = baseColor

        exampleCommitFix.lines.forEachIndexed { index, exampleLine ->
            val lineText = "%6d %c %s".format(
                exampleLine.lineNumber,
                when (exampleLine.lineChange) {
                    "added" -> '+'
                    "removed" -> '-'
                    "none" -> ' '
                    else -> '!'
                },
                exampleLine.line
            )
            val codeLine = JTextArea(lineText)

            codeLine.background = when (exampleLine.lineChange) {
                "added" -> addedColor
                "removed" -> removedColor
                "none" -> baseColor
                else -> baseColor
            }
            codeLine.isOpaque = true
            codeLine.isEditable = false
            codeLine.font = io.snyk.plugin.ui.getFont(-1, 14, codeLine.font)

            panel.add(
                codeLine,
                baseGridConstraintsAnchorWest(
                    row = index,
                    fill = GridConstraints.FILL_BOTH,
                    indent = 0
                )
            )
        }

        // fill space with empty lines to avoid rows stretching
        for (i in exampleCommitFix.lines.size..rowCount) {
            val emptyLine = JTextArea("")
            panel.add(
                emptyLine,
                baseGridConstraintsAnchorWest(
                    row = i - 1,
                    fill = GridConstraints.FILL_BOTH,
                    indent = 0
                )
            )
        }

        return ScrollPaneFactory.createScrollPane(
            panel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        )
    }

    private fun getOverviewText(): String {
        return issue.additionalData.message
    }
}

private fun getIssueTitle(issue: ScanIssue) =
    if (issue.additionalData.isSecurityType) {
        issue.title.split(":").firstOrNull() ?: "Unknown issue"
    } else {
        issue.additionalData.message.split(".").firstOrNull() ?: "Unknown issue"
    }
