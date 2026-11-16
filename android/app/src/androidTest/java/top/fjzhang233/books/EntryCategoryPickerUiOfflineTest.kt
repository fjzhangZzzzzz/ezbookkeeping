package top.fjzhang233.books

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EntryCategoryPickerUiOfflineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun childrenPanelIsInsertedAfterExpandedParentRow() {
        val categories = buildList {
            repeat(10) { index ->
                val parent = "p${index + 1}"
                add(JSONObject().put("id", parent).put("parentId", "0").put("type", 2).put("name", "大类${index + 1}"))
                add(JSONObject().put("id", "c${index + 1}").put("parentId", parent).put("type", 2).put("name", "子类"))
            }
        }
        compose.setContent {
            FamilyTheme {
                EntryCategoryPicker(
                    categories,
                    transactionType = 3,
                    selectedId = "",
                    enabled = true,
                    onSelect = {},
                    // The production host applies WindowInsets.safeDrawing; reserve the same visual band in this fixture.
                    modifier = Modifier.fillMaxSize().padding(top = 24.dp, bottom = 24.dp)
                )
            }
        }

        compose.onNodeWithTag("entry-category-p6").assertIsDisplayed()
        compose.onNodeWithTag("entry-category-c1").assertIsDisplayed()
        val firstChildTop = compose.onNodeWithTag("entry-category-c1").fetchSemanticsNode().boundsInRoot.top
        val secondRowTop = compose.onNodeWithTag("entry-category-p6").fetchSemanticsNode().boundsInRoot.top
        assertTrue("first row child panel must precede the second parent row", firstChildTop < secondRowTop)

        compose.onNodeWithTag("entry-category-p6").performClick()
        compose.onNodeWithTag("entry-category-c6").assertIsDisplayed()
        val selectedParentTop = compose.onNodeWithTag("entry-category-p6").fetchSemanticsNode().boundsInRoot.top
        val secondChildTop = compose.onNodeWithTag("entry-category-c6").fetchSemanticsNode().boundsInRoot.top
        assertTrue("expanded second row child panel must follow its parent row", secondChildTop > selectedParentTop)
    }
}
