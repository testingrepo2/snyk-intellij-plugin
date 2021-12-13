package io.snyk.plugin.events

import com.intellij.util.messages.Topic
import io.snyk.plugin.snykcode.SnykCodeResults
import snyk.common.SnykError
import snyk.container.ContainerResult
import snyk.iac.IacResult
import snyk.oss.OssResult

interface SnykScanListener {
    companion object {
        val SNYK_SCAN_TOPIC =
            Topic.create("Snyk scan", SnykScanListener::class.java)
    }

    fun scanningStarted()

    fun scanningOssFinished(ossResult: OssResult)

    fun scanningSnykCodeFinished(snykCodeResults: SnykCodeResults?)

    fun scanningIacFinished(iacResult: IacResult)

    fun scanningContainerFinished(containerResult: ContainerResult)

    fun scanningOssError(snykError: SnykError)

    fun scanningIacError(snykError: SnykError)

    fun scanningSnykCodeError(snykError: SnykError)

    fun scanningContainerError(error: SnykError)
}
