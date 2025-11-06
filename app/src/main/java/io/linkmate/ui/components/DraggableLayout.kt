package io.linkmate.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Widget尺寸定义
 */
data class WidgetSize(
    val width: Int, // 列数
    val height: Int // 行数
) {
    companion object {
        val Size1x1 = WidgetSize(1, 1)
        val Size1x2 = WidgetSize(1, 2)
        val Size1x3 = WidgetSize(1, 3)
        val Size5x1 = WidgetSize(5, 1)
    }
}

/**
 * 可拖拽的布局�?
 */
data class DraggableItem<T>(
    val id: String,
    val data: T,
    val size: WidgetSize = WidgetSize.Size1x1, // 默认1x1
    val composable: @Composable () -> Unit
)

/**
 * 可拖拽的垂直布局
 * 基于拖拽偏移量简单计算目标位�?
 */
@Composable
fun <T> DraggableColumn(
    items: List<DraggableItem<T>>,
    onReorder: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp)
) {
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var draggedOverIndex by remember { mutableIntStateOf(-1) }

    Column(
        modifier = modifier,
        verticalArrangement = verticalArrangement
    ) {
        items.forEachIndexed { index, item ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (draggingIndex < 0) {
                            Modifier.pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingIndex = index
                                        dragOffset = 0f
                                    },
                                    onDrag = { _, dragAmount ->
                                        dragOffset += dragAmount.y
                                        
                                        // 简单计算：�?50dp为一个区�?
                                        val itemHeightWithSpacing = 150f // 估算高度+间距
                                        val numItemsPassed = (dragOffset / itemHeightWithSpacing).toInt()
                                        val targetIndex = (index + numItemsPassed).coerceIn(0, items.size - 1)
                                        
                                        if (targetIndex != draggedOverIndex) {
                                            draggedOverIndex = targetIndex
                                        }
                                    },
                                    onDragEnd = {
                                        if (draggingIndex >= 0 && draggedOverIndex >= 0 && draggedOverIndex != draggingIndex) {
                                            onReorder(draggingIndex, draggedOverIndex)
                                        }
                                        draggingIndex = -1
                                        draggedOverIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingIndex = -1
                                        draggedOverIndex = -1
                                        dragOffset = 0f
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
            ) {
                if (draggingIndex == index) {
                    // 拖拽中的预览
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(0, dragOffset.roundToInt()) }
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    ) {
                        item.composable()
                    }
                } else if (draggedOverIndex == index) {
                    // 目标位置高亮
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        item.composable()
                    }
                } else {
                    item.composable()
                }
            }
        }
    }
}
