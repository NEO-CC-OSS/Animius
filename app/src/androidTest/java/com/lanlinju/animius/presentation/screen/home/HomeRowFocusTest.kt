package com.lanlinju.animius.presentation.screen.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lanlinju.animius.domain.model.Anime
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L3 交互测试首片：HomeRow 的焦点移动、行边界与确定键回调。
 *
 * 只验证组件行为，不依赖网络/数据库/偏好；图片走空 URL 的本地占位图。
 * 左右两侧放可聚焦哨兵，用来证明行首/行尾不会把焦点让出去。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HomeRowFocusTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val clicked = mutableListOf<String>()
    private lateinit var lastRow: MutableState<Int>
    private lateinit var lastItem: MutableState<Int>
    private lateinit var requesters: MutableMap<Long, FocusRequester>

    private val row0 = listOf(anime("R0A"), anime("R0B"), anime("R0C"))
    private val row1 = listOf(anime("R1A"), anime("R1B"), anime("R1C"))

    @Before
    fun resetClicks() {
        clicked.clear()
    }

    private fun anime(title: String) = Anime(
        title = title,
        img = "",
        detailUrl = "https://example.invalid/$title",
        episodeName = "",
    )

    private fun showRows() {
        lastRow = mutableStateOf(0)
        lastItem = mutableStateOf(0)
        requesters = mutableMapOf()
        composeRule.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                Row(Modifier.fillMaxSize()) {
                    Button(
                        onClick = { clicked += "LEFT_SENTINEL" },
                        modifier = Modifier.testTag("left-sentinel"),
                    ) { Text("LEFT") }
                    Column(Modifier.weight(1f)) {
                        HomeRow(
                            list = row0,
                            title = "Row 0",
                            rowIndex = 0,
                            lastFocusedRow = lastRow,
                            lastFocusedItem = lastItem,
                            itemRequesters = requesters,
                            onItemClicked = { clicked += it.title },
                        )
                        HomeRow(
                            list = row1,
                            title = "Row 1",
                            rowIndex = 1,
                            lastFocusedRow = lastRow,
                            lastFocusedItem = lastItem,
                            itemRequesters = requesters,
                            onItemClicked = { clicked += it.title },
                        )
                    }
                    Button(
                        onClick = { clicked += "RIGHT_SENTINEL" },
                        modifier = Modifier.testTag("right-sentinel"),
                    ) { Text("RIGHT") }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun focusFirstItem() {
        composeRule.runOnIdle { requesters.getValue(0L).requestFocus() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("home-item-0-0").assertIsFocused()
    }

    private fun press(key: Key) {
        composeRule.onRoot().performKeyInput { pressKey(key) }
        composeRule.waitForIdle()
    }

    @Test
    fun horizontalMoveAndReturn() {
        showRows()
        focusFirstItem()

        press(Key.DirectionRight)
        composeRule.onNodeWithTag("home-item-0-1").assertIsFocused()
        assertEquals(0, lastRow.value)
        assertEquals(1, lastItem.value)

        press(Key.DirectionLeft)
        composeRule.onNodeWithTag("home-item-0-0").assertIsFocused()
        assertEquals(0, lastRow.value)
        assertEquals(0, lastItem.value)
    }

    @Test
    fun horizontalBoundaryDoesNotEscapeToSentinels() {
        showRows()
        focusFirstItem()

        press(Key.DirectionLeft)
        composeRule.onNodeWithTag("home-item-0-0").assertIsFocused()
        composeRule.onNodeWithTag("left-sentinel").assertIsNotFocused()

        repeat(2) { press(Key.DirectionRight) }
        composeRule.onNodeWithTag("home-item-0-2").assertIsFocused()
        press(Key.DirectionRight)
        composeRule.onNodeWithTag("home-item-0-2").assertIsFocused()
        composeRule.onNodeWithTag("right-sentinel").assertIsNotFocused()
    }

    @Test
    fun verticalMoveBetweenRows() {
        showRows()
        focusFirstItem()

        press(Key.DirectionDown)
        composeRule.onNodeWithTag("home-item-1-0").assertIsFocused()
        assertEquals(1, lastRow.value)

        press(Key.DirectionUp)
        composeRule.onNodeWithTag("home-item-0-0").assertIsFocused()
        assertEquals(0, lastRow.value)
    }

    @Test
    fun verticalMoveFromLastItem() {
        showRows()
        focusFirstItem()

        repeat(2) { press(Key.DirectionRight) }
        composeRule.onNodeWithTag("home-item-0-2").assertIsFocused()

        press(Key.DirectionDown)
        composeRule.onNodeWithTag("home-item-1-2").assertIsFocused()
        assertEquals(1, lastRow.value)

        press(Key.DirectionUp)
        composeRule.onNodeWithTag("home-item-0-2").assertIsFocused()
        assertEquals(0, lastRow.value)
    }

    @Test
    fun centerKeyInvokesClickWithFocusedAnime() {
        showRows()
        focusFirstItem()

        press(Key.DirectionRight)
        press(Key.DirectionCenter)

        assertEquals(listOf("R0B"), clicked)
    }
}
