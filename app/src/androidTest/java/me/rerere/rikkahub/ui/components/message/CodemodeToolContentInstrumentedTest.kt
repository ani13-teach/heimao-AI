package me.rerere.rikkahub.ui.components.message

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.ui.CodemodeExecution
import me.rerere.ai.ui.CodemodeOperation
import me.rerere.ai.ui.CodemodeStatus
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.ui.components.ui.ChainOfThought
import me.rerere.rikkahub.ui.theme.Typography
import org.junit.Rule
import org.junit.Test
import java.io.File

class CodemodeToolContentInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun narrowLightViewExpandsFailedOperation() = renderAndCheck(false, 320, CodemodeStatus.FAILED)

    @Test fun darkViewPreservesCancelledStatus() = renderAndCheck(true, 390, CodemodeStatus.CANCELLED)

    private fun renderAndCheck(dark: Boolean, width: Int, status: CodemodeStatus) {
        val execution = CodemodeExecution(
            startedAt = 1, finishedAt = 5, status = status,
            error = if (status == CodemodeStatus.FAILED) "文件不存在" else null,
            operations = listOf(
                CodemodeOperation("1", "workspace_write_file", JsonPrimitive("/workspace/项目/文件.txt"),
                    1, 2, CodemodeStatus.SUCCEEDED, JsonPrimitive("已写入")),
                CodemodeOperation("2", "workspace_read_file", JsonPrimitive("/workspace/" + "long-path/".repeat(12)),
                    3, 4, status, error = if (status == CodemodeStatus.FAILED) "文件不存在" else "已取消"),
            ),
        )
        val tool = UIMessagePart.Tool("parent", "codemode", "{\"code\":\"text('done')\"}",
            output = listOf(UIMessagePart.Text("done")))
        compose.setContent {
            MaterialExpressiveTheme(
                colorScheme = if (dark) darkColorScheme() else lightColorScheme(),
                typography = Typography,
            ) {
                Surface(Modifier.width(width.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        ChainOfThought(modifier = Modifier.fillMaxWidth(), steps = listOf(tool)) {
                            CodemodeToolContent(it, execution)
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Codemode").assertIsDisplayed().performClick()
        compose.onNodeWithText("1. workspace_write_file").assertIsDisplayed()
        compose.onNodeWithText("2. workspace_read_file").assertIsDisplayed().performClick()
        compose.onNodeWithText("参数").assertIsDisplayed()
        compose.onNodeWithText(execution.summary()).assertIsDisplayed()
        val filename = "codemode-${if (dark) "dark" else "light"}-$width.png"
        val target = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, filename)
        target.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
