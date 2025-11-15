package io.linkmate.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import android.util.Log
import kotlin.math.roundToInt

private const val TAG = "DraggableGrid"

/**
 * 网格单元格定位信息
 */
private data class PositionedItem<T>(
    val item: DraggableItem<T>,
    val x: Int, // 列位置（0 开始）
    val y: Int, // 行位置（0 开始）
    val width: Int,
    val height: Int
)

/**
 * 基于坐标的网格布局
 *
 * @param itemPositions 每个项的位置映射 (item.id -> WidgetPosition)
 * @param onPositionUpdate 当项的位置改变时的回调 (itemId, newPosition)
 * @param maxColumns 网格的最大列数（默认根据屏幕宽度自动计算）
 * @param maxRows 网格的最大行数（null 或 -1 表示无限行，默认无限）
 * @param modifier 修饰符
 * @param cellSize 每个网格单元格的大小
 * @param horizontalSpacing 水平间距
 * @param verticalSpacing 垂直间距
 */
@Composable
fun <T> DraggableGrid(
    items: List<DraggableItem<T>>,
    itemPositions: Map<String, WidgetPosition>,
    onPositionUpdate: (String, WidgetPosition) -> Unit,
    maxColumns: Int? = null,
    maxRows: Int? = null,
    modifier: Modifier = Modifier,
    cellSize: androidx.compose.ui.unit.Dp = 100.dp,
    horizontalSpacing: androidx.compose.ui.unit.Dp = 8.dp,
    verticalSpacing: androidx.compose.ui.unit.Dp = 8.dp
) {
    // 使用 derivedStateOf 确保 itemPositions 总是最新的
    val latestItemPositions by rememberUpdatedState(itemPositions)
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val cellSizePx = with(density) { cellSize.toPx() }
    val horizontalSpacingPx = with(density) { horizontalSpacing.toPx() }
    val verticalSpacingPx = with(density) { verticalSpacing.toPx() }
    
    // 计算实际列数
    val actualMaxColumns = remember(maxColumns, screenWidthPx, cellSizePx, horizontalSpacingPx) {
        maxColumns ?: ((screenWidthPx + horizontalSpacingPx) / (cellSizePx + horizontalSpacingPx)).toInt().coerceAtLeast(1)
    }
    
    // 实际最大行数（null 表示无限
    val actualMaxRows = maxRows?.takeIf { it > 0 }
    
    // 拖拽状态
    var draggingItemId by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableStateOf(0f) } // 拖拽偏移量（手指相对于图标中心的偏移）
    var dragOffsetY by remember { mutableStateOf(0f) } // 拖拽偏移量（手指相对于图标中心的偏移）
    var targetGridX by remember { mutableStateOf(-1) }
    var targetGridY by remember { mutableStateOf(-1) }
    
    // 根据位置映射计算每个项的实际位置（使用最新的位置）
    val positionedItems = remember(items, latestItemPositions, actualMaxColumns, actualMaxRows) {
        Log.d(TAG, "positionedItems 重新计算: items.size=${items.size}")
        items.forEach { item ->
            Log.d(TAG, "  处理项:${item.id}, size=${item.size.width}x${item.size.height}")
        }
        val result = mutableListOf<PositionedItem<T>>()
        val occupied = mutableSetOf<Pair<Int, Int>>()
        
        // 先处理有明确位置的项，检测冲突
        val itemsWithPositions = items.mapNotNull { item ->
            val position = latestItemPositions[item.id]
            if (position != null) {
                item to position
            } else {
                null
            }
        }

        // widgetOrder 顺序处理
        val sortedItemsWithPositions = itemsWithPositions.sortedByDescending { it.first.size.width * it.first.size.height }
        
        sortedItemsWithPositions.forEach { (item, position) ->
            val width = item.size.width
            val height = item.size.height
            
            // 确保位置在有效范围内
            val x = position.x.coerceIn(0, (actualMaxColumns - width).coerceAtLeast(0))
            val maxY = actualMaxRows?.let { (it - height).coerceAtLeast(0) }
            val y = if (maxY != null) {
                position.y.coerceIn(0, maxY)
            } else {
                position.y.coerceAtLeast(0)
            }
            
            // 检查当前位置是否被占用
            val wouldOccupy = mutableSetOf<Pair<Int, Int>>()
            for (dy in 0 until height) {
                for (dx in 0 until width) {
                    if (x + dx < actualMaxColumns) {
                        wouldOccupy.add(Pair(x + dx, y + dy))
                    }
                }
            }
            
            val conflicts = wouldOccupy.intersect(occupied)
            
            if (conflicts.isEmpty()) {
                // 没有冲突，可以放
                result.add(PositionedItem(item, x, y, width, height))
                occupied.addAll(wouldOccupy)
            } else {
                // 有冲突，需要重新分配
                Log.d(TAG, "检测到 ${item.id} 位置冲突，尝试重新分配位位置")
                // 不添加该项到result，让它在第二遍自动分配中处理
            }
        }
        
        // 第二遍：为没有位置的项（以及第一遍冲突的项）自动分配位置
        items.forEach { item ->
            // 检查该项是否已经在result中（第一遍成功放置
            val alreadyPlaced = result.any { it.item.id == item.id }
            if (!alreadyPlaced) {
                val width = item.size.width
                val height = item.size.height
                
                var found = false
                var x = 0
                var y = 0
                var attempts = 0
                val maxAttempts = 500
                
                while (!found && attempts < maxAttempts) {
                    // 检查行数限制
                    if (actualMaxRows != null && y + height > actualMaxRows) {
                        break // 超出最大行数，停止查找
                    }
                    
                    if (x + width > actualMaxColumns) {
                        x = 0
                        y++
                        continue
                    }
                    
                    // 检查是否可以放
                    val canPlace = (0 until height).all { dy ->
                        (0 until width).all { dx ->
                            Pair(x + dx, y + dy) !in occupied
                        }
                    }
                    
                    if (canPlace) {
                        found = true
                        result.add(PositionedItem(item, x, y, width, height))
                        
                        // 标记占用的网格
                        for (dy in 0 until height) {
                            for (dx in 0 until width) {
                                occupied.add(Pair(x + dx, y + dy))
                            }
                        }
                    } else {
                        x++
                    }
                    attempts++
                }
                
                if (!found) {
                    Log.e(TAG, "无法为项自动分配位置: ${item.id}")
                }
            }
        }
        
        result
    }
    
    // 计算内容总高度 用于滚动容器
    val contentHeight = remember(positionedItems, cellSizePx, verticalSpacingPx) {
        if (positionedItems.isEmpty()) {
            cellSizePx
        } else {
            val maxBottom = positionedItems.maxOfOrNull { positioned ->
                val itemHeight = positioned.height * cellSizePx + (positioned.height - 1) * verticalSpacingPx
                (positioned.y * (cellSizePx + verticalSpacingPx)) + itemHeight
            } ?: cellSizePx
            maxBottom + cellSizePx // 添加一些底部边
        }
    }
    
    val scrollState = rememberScrollState()
    
    // 计算目标网格坐标
    // offsetX 和 offsetY 是手指相对于图标中心的偏移量
    fun calculateTargetGrid(
        item: PositionedItem<T>,
        offsetX: Float,
        offsetY: Float
    ): Pair<Int, Int> {
        val originalXPx = item.x * (cellSizePx + horizontalSpacingPx)
        val originalYPx = item.y * (cellSizePx + verticalSpacingPx)
        
        // 计算图标中心位置
        val itemWidthPx = item.width * cellSizePx + (item.width - 1) * horizontalSpacingPx
        val itemHeightPx = item.height * cellSizePx + (item.height - 1) * verticalSpacingPx
        val itemCenterOffsetXPx = itemWidthPx / 2f
        val itemCenterOffsetYPx = itemHeightPx / 2f
        
        val originalCenterXPx = originalXPx + itemCenterOffsetXPx
        val originalCenterYPx = originalYPx + itemCenterOffsetYPx
        
        // 手指当前位置（在容器坐标系中）
        val currentFingerXPx = originalCenterXPx + offsetX
        val currentFingerYPx = originalCenterYPx + offsetY
        
        // 计算图标左上角的目标位置（让图标中心跟随手指）
        val targetIconLeftXPx = currentFingerXPx - itemCenterOffsetXPx
        val targetIconLeftYPx = currentFingerYPx - itemCenterOffsetYPx
        
        // 转换为网格坐标（使用更精确的边界检测）
        // 使用图标中心点来计算目标网格，而不是左上角，这样可以更准确地处理边界
        val cellWidth = cellSizePx + horizontalSpacingPx
        val cellHeight = cellSizePx + verticalSpacingPx
        
        // 计算图标中心点对应的网格坐标
        val centerGridX = currentFingerXPx / cellWidth
        val centerGridY = currentFingerYPx / cellHeight
        
        // 将中心点坐标转换为左上角网格坐标
        // 如果图标中心在某个网格的右半部分，应该放在下一个网格
        val gridX = (centerGridX - (item.width - 1) / 2f).toInt().coerceAtLeast(0)
        val gridY = (centerGridY - (item.height - 1) / 2f).toInt().coerceAtLeast(0)
        
        // 确保在有效范围内
        val validX = gridX.coerceIn(0, (actualMaxColumns - item.width).coerceAtLeast(0))
        val maxY = actualMaxRows?.let { (it - item.height).coerceAtLeast(0) }
        val validY = if (maxY != null) {
            gridY.coerceIn(0, maxY)
        } else {
            gridY.coerceAtLeast(0)
        }
        
        return Pair(validX, validY)
    }
    
    Box(
        modifier = modifier
            .verticalScroll(scrollState) // 添加垂直滚动
    ) {
        // 创建一个固定高度的容器来容纳所有项
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { contentHeight.toDp() })
        ) {
            // 渲染所有项
            positionedItems.forEach { positioned ->
                key(positioned.item.id) {
                    val itemId = positioned.item.id
                    val isDragging = draggingItemId == itemId
                    
                    // 计算显示位置
                    val originalXPx = positioned.x * (cellSizePx + horizontalSpacingPx)
                    val originalYPx = positioned.y * (cellSizePx + verticalSpacingPx)
                    
                    // 计算项的中心点位置（相对于图标左上角的偏移）
                    val itemWidthPx = positioned.width * cellSizePx + (positioned.width - 1) * horizontalSpacingPx
                    val itemHeightPx = positioned.height * cellSizePx + (positioned.height - 1) * verticalSpacingPx
                    val itemCenterOffsetXPx = itemWidthPx / 2f
                    val itemCenterOffsetYPx = itemHeightPx / 2f
                    
                    val displayX = if (isDragging) {
                        // 拖拽中：让图标中心跟随手指
                        // 手指当前位置（在容器坐标系中）= 原始图标中心位置 + dragOffset
                        // 原始图标中心位置 = 原始左上角位置 + 图标中心偏移
                        // 图标左上角位置 = 手指当前位置 - 图标中心偏移
                        val originalCenterXPx = originalXPx + itemCenterOffsetXPx
                        val currentFingerXPx = originalCenterXPx + dragOffsetX
                        currentFingerXPx - itemCenterOffsetXPx
                    } else {
                        // 正常状态：使用网格位置
                        originalXPx
                    }
                    
                    val displayY = if (isDragging) {
                        // 拖拽中：让图标中心跟随手指
                        val originalCenterYPx = originalYPx + itemCenterOffsetYPx
                        val currentFingerYPx = originalCenterYPx + dragOffsetY
                        currentFingerYPx - itemCenterOffsetYPx
                    } else {
                        // 正常状态：使用网格位置
                        originalYPx
                    }
                    
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(displayX.toInt(), displayY.toInt()) }
                            .width(with(density) { 
                                cellSize * positioned.width + horizontalSpacing * (positioned.width - 1) 
                            })
                            .height(with(density) { 
                                cellSize * positioned.height + verticalSpacing * (positioned.height - 1) 
                            })
                            .graphicsLayer(
                                alpha = if (isDragging) 0.85f else 1f,
                                scaleX = if (isDragging) 1.08f else 1f,
                                scaleY = if (isDragging) 1.08f else 1f
                            )
                            .zIndex(if (isDragging) 100f else 0f)
                            .pointerInput(itemId) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { offset ->
                                        // offset 是手指在组件内的位置（相对于组件左上角）
                                        // 我们希望图标中心跟随手指，所以初始偏移就是手指相对于图标中心的偏移
                                        // 这样图标中心会立即移动到手指位置
                                        val dragItemWidthPx = positioned.width * cellSizePx + (positioned.width - 1) * horizontalSpacingPx
                                        val dragItemHeightPx = positioned.height * cellSizePx + (positioned.height - 1) * verticalSpacingPx
                                        val dragItemCenterOffsetXPx = dragItemWidthPx / 2f
                                        val dragItemCenterOffsetYPx = dragItemHeightPx / 2f
                                        
                                        // 手指相对于图标中心的偏移
                                        dragOffsetX = offset.x - dragItemCenterOffsetXPx
                                        dragOffsetY = offset.y - dragItemCenterOffsetYPx
                                        
                                        draggingItemId = itemId
                                        targetGridX = -1
                                        targetGridY = -1
                                        
                                        Log.d(TAG, "开始拖拽: $itemId, 手指在图标内位置: (${offset.x}, ${offset.y}), 中心偏移: ($dragItemCenterOffsetXPx, $dragItemCenterOffsetYPx), 初始dragOffset: ($dragOffsetX, $dragOffsetY)")
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        
                                        // 累加拖拽偏移量，让图标完全跟随手指移动
                                        // dragAmount 是相对于上一次事件的移动距离
                                        dragOffsetX += dragAmount.x
                                        dragOffsetY += dragAmount.y
                                        
                                        // 计算目标网格位置（用于显示高亮指示器）
                                        val (newX, newY) = calculateTargetGrid(positioned, dragOffsetX, dragOffsetY)
                                        
                                        if (newX != targetGridX || newY != targetGridY) {
                                            targetGridX = newX
                                            targetGridY = newY
                                            // 使用 positioned 中的尺寸，这是最新的
                                            Log.d(TAG, "目标位置: ($targetGridX, $targetGridY), 被拖拽项: ${positioned.item.id}, 尺寸: ${positioned.width}x${positioned.height}")
                                        }
                                    },
                                    onDragEnd = {
                                        val draggedId = draggingItemId
                                        if (draggedId != null && targetGridX >= 0 && targetGridY >= 0) {
                                            // 使用最新的位置信息，而不是闭包捕获的旧值
                                            val currentPositions = latestItemPositions
                                            val currentPos = currentPositions[draggedId]
                                            val newPos = WidgetPosition(targetGridX, targetGridY)
                                            
                                            // 从 items 中获取被拖拽项的尺寸（使用最新的数据）
                                            val draggedItem = items.find { it.id == draggedId }
                                            if (draggedItem != null) {
                                                val draggedItemWidth = draggedItem.size.width
                                                val draggedItemHeight = draggedItem.size.height

                                                // 检查目标位置是否已经被占用（基于最新的 itemPositions，排除被拖拽的项）
                                                // 创建一个临时的位置映射，移除被拖拽项的位置，这样它不会影响占用检查
                                                val tempItemPositions = currentPositions.toMutableMap()
                                                tempItemPositions.remove(draggedId)
                                                
                                                // 查找占用目标位置的项
                                                // 计算被拖拽项将占用的所有网格单元
                                                val targetOccupied = mutableSetOf<Pair<Int, Int>>()
                                                for (dy in 0 until draggedItemHeight) {
                                                    for (dx in 0 until draggedItemWidth) {
                                                        val gridX = targetGridX + dx
                                                        val gridY = targetGridY + dy
                                                        if (gridX < actualMaxColumns) {
                                                            targetOccupied.add(Pair(gridX, gridY))
                                                        }
                                                    }
                                                }

                                                // 检查哪些项占用了这些网格单元
                                                val conflictingItems = items.filter { item ->
                                                    val isNotDragged = item.id != draggedId
                                                    if (isNotDragged) {
                                                        val itemPos = tempItemPositions[item.id]
                                                        if (itemPos != null) {
                                                            // 计算该项占用的所有网格单元
                                                            val itemOccupied = mutableSetOf<Pair<Int, Int>>()
                                                            for (dy in 0 until item.size.height) {
                                                                for (dx in 0 until item.size.width) {
                                                                    val gridX = itemPos.x + dx
                                                                    val gridY = itemPos.y + dy
                                                                    if (gridX < actualMaxColumns) {
                                                                        itemOccupied.add(Pair(gridX, gridY))
                                                                    }
                                                                }
                                                            }
                                                            // 检查是否有重叠的网格单元
                                                            targetOccupied.intersect(itemOccupied).isNotEmpty()
                                                        } else {
                                                            false // 没有位置的项不算占用
                                                        }
                                                    } else {
                                                        false // 被拖拽的项本身不算占用
                                                    }
                                                }
                                                
                                                if (conflictingItems.isNotEmpty()) {
                                                    // 如果只有一个冲突项，且尺寸相同，允许交换
                                                    val canSwap = conflictingItems.size == 1 && 
                                                                  currentPos != null &&
                                                                  draggedItemWidth == conflictingItems[0].size.width &&
                                                                  draggedItemHeight == conflictingItems[0].size.height
                                                    
                                                    if (canSwap) {
                                                        // 交换位置
                                                        val conflictingItem = conflictingItems[0]
                                                        val conflictingItemPos = tempItemPositions[conflictingItem.id]
                                                        if (conflictingItemPos != null && currentPos != null) {
                                                            Log.d(TAG, "交换位置: $draggedId (${currentPos.x}, ${currentPos.y}) <-> ${conflictingItem.id} (${conflictingItemPos.x}, ${conflictingItemPos.y})")
                                                            onPositionUpdate(draggedId, newPos)
                                                            onPositionUpdate(conflictingItem.id, io.linkmate.ui.components.WidgetPosition(currentPos.x, currentPos.y))
                                                        }
                                                    } else {
                                                        // 尝试自动移动冲突项到新位置

                                                        Log.d(TAG, "检测到冲突，尝试自动移动冲突项")
                                                        Log.d(TAG, "被拖拽项: $draggedId，目标位置: ($targetGridX, $targetGridY)")
                                                        Log.d(TAG, "被拖拽项目标占用区域: $targetOccupied")
                                                        
                                                        // 收集所有已占用的位置（包括被拖拽项的原位置，如果存在）
                                                        val allOccupied = targetOccupied.toMutableSet()
                                                        
                                                        // 添加所有非冲突项占用的位置
                                                        items.forEach { item ->
                                                            if (item.id != draggedId && !conflictingItems.contains(item)) {
                                                                val itemPos = tempItemPositions[item.id]
                                                                if (itemPos != null) {
                                                                    for (dy in 0 until item.size.height) {
                                                                        for (dx in 0 until item.size.width) {
                                                                            val gridX = itemPos.x + dx
                                                                            val gridY = itemPos.y + dy
                                                                            if (gridX < actualMaxColumns) {
                                                                                allOccupied.add(Pair(gridX, gridY))
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }

                                                        // 尝试移动每个冲突项

                                                        var allMoved = true
                                                        val movedItems = mutableListOf<Pair<String, WidgetPosition>>()
                                                        
                                                        conflictingItems.forEach { conflictingItem ->
                                                            val conflictingItemPos = tempItemPositions[conflictingItem.id]
                                                            if (conflictingItemPos != null) {
                                                                // 查找新位置（从目标位置右侧开始，然后向下扫描）

                                                                var found = false
                                                                var newX = targetGridX + draggedItemWidth
                                                                var newY = targetGridY
                                                                val maxAttempts = 500
                                                                var attempts = 0
                                                                
                                                                while (!found && attempts < maxAttempts) {
                                                                    if (newX + conflictingItem.size.width > actualMaxColumns) {
                                                                        newX = 0
                                                                        newY++
                                                                        if (actualMaxRows != null && newY + conflictingItem.size.height > actualMaxRows) {
                                                                            break
                                                                        }
                                                                        attempts++
                                                                        continue
                                                                    }

                                                                    // 检查这个位置是否可用

                                                                    val wouldOccupy = mutableSetOf<Pair<Int, Int>>()
                                                                    for (dy in 0 until conflictingItem.size.height) {
                                                                        for (dx in 0 until conflictingItem.size.width) {
                                                                            val gridX = newX + dx
                                                                            val gridY = newY + dy
                                                                            if (gridX < actualMaxColumns) {
                                                                                wouldOccupy.add(Pair(gridX, gridY))
                                                                            }
                                                                        }
                                                                    }
                                                                    
                                                                    // 检查是否与已占用的位置冲突
                                                                    if (wouldOccupy.intersect(allOccupied).isEmpty()) {
                                                                        found = true
                                                                        val newPosition = WidgetPosition(newX, newY)
                                                                        movedItems.add(conflictingItem.id to newPosition)
                                                                        allOccupied.addAll(wouldOccupy)
                                                                        Log.d(TAG, "  自动移动冲突项: ${conflictingItem.id} 从 (${conflictingItemPos.x}, ${conflictingItemPos.y}) 到 ($newX, $newY)")

                                                                    } else {
                                                                        newX++
                                                                    }
                                                                    attempts++
                                                                }
                                                                
                                                                if (!found) {
                                                                    allMoved = false
                                                                    Log.w(TAG, "  无法为冲突项 ${conflictingItem.id} 找到新位置")
                                                                }
                                                            }
                                                        }
                                                        
                                                        if (allMoved && movedItems.isNotEmpty()) {
                                                            // 所有冲突项都可以移动，执行移动
                                                            movedItems.forEach { (itemId, newPosition) ->
                                                                onPositionUpdate(itemId, newPosition)
                                                            }
                                                            // 放置被拖拽项
                                                            Log.d(TAG, "所有冲突项已移动，放置被拖拽项($targetGridX, $targetGridY)")
                                                            onPositionUpdate(draggedId, newPos)
                                                        } else {
                                                            // 无法移动所有冲突项，阻止放置

                                                            Log.d(TAG, "无法自动移动所有冲突项，取消")
                                                            conflictingItems.forEach { item ->
                                                                val itemPos = tempItemPositions[item.id]
                                                                if (itemPos != null) {
                                                                    val itemOccupied = mutableSetOf<Pair<Int, Int>>()
                                                                    for (dy in 0 until item.size.height) {
                                                                        for (dx in 0 until item.size.width) {
                                                                            val gridX = itemPos.x + dx
                                                                            val gridY = itemPos.y + dy
                                                                            if (gridX < actualMaxColumns) {
                                                                                itemOccupied.add(Pair(gridX, gridY))
                                                                            }
                                                                        }
                                                                    }
                                                                    val overlapCells = targetOccupied.intersect(itemOccupied)
                                                                    Log.d(TAG, "  冲突项目${item.id}，位置 (${itemPos.x}, ${itemPos.y}), 大小: ${item.size.width}x${item.size.height}")
                                                                    Log.d(TAG, "    占用区域: $itemOccupied")
                                                                    Log.d(TAG, "    重叠区域: $overlapCells")
                                                                }
                                                            }
                                                        }
                                                    }
                                                } else {
                                                    // 没有冲突，可以放置
                                                    if (currentPos == null || currentPos.x != newPos.x || currentPos.y != newPos.y) {
                                                        Log.d(TAG, "更新位置: $draggedId -> ($targetGridX, $targetGridY)")
                                                        onPositionUpdate(draggedId, newPos)
                                                    }
                                                }
                                            } else {
                                                Log.e(TAG, "无法找到被拖拽项: $draggedId")
                                            }
                                        }
                                        
                                    // 重置拖拽状态
                                    draggingItemId = null
                                    dragOffsetX = 0f
                                    dragOffsetY = 0f
                                    targetGridX = -1
                                    targetGridY = -1
                                },
                                onDragCancel = {
                                    Log.d(TAG, "拖拽取消")
                                    draggingItemId = null
                                    dragOffsetX = 0f
                                    dragOffsetY = 0f
                                    targetGridX = -1
                                    targetGridY = -1
                                }
                                )
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            positioned.item.composable()
                        }
                    }
                }
            }
        }
        
        // 目标位置高亮指示器（在滚动Box内，但不在固定高度容器内
        if (draggingItemId != null && targetGridX >= 0 && targetGridY >= 0) {
            items.find { it.id == draggingItemId }?.let { draggedItem ->
                val targetXPx = targetGridX * (cellSizePx + horizontalSpacingPx)
                val targetYPx = targetGridY * (cellSizePx + verticalSpacingPx)
                
                // 检查目标位置是否被占用（基于最新的 itemPositions，排除被拖拽的项目
                // 注意：排除被拖拽项自己的位置
                val currentPositions = latestItemPositions
                // 创建一个临时位置映射，移除被拖拽项，确保检查时不包含它
                val tempItemPositions = currentPositions.toMutableMap()
                tempItemPositions.remove(draggingItemId)
                
                val isPositionOccupied = items.any { item ->
                    val isNotDragged = item.id != draggingItemId
                    if (isNotDragged) {
                        val itemPos = tempItemPositions[item.id]
                        if (itemPos != null) {
                            val targetRight = targetGridX + draggedItem.size.width
                            val targetBottom = targetGridY + draggedItem.size.height
                            val itemRight = itemPos.x + item.size.width
                            val itemBottom = itemPos.y + item.size.height
                            
                            targetGridX < itemRight && targetRight > itemPos.x &&
                            targetGridY < itemBottom && targetBottom > itemPos.y
                        } else {
                            false // 没有位置的项不算占用
                        }
                    } else {
                        false
                    }
                }
                
                Box(
                    modifier = Modifier
                        .offset { IntOffset(targetXPx.toInt(), targetYPx.toInt()) }
                        .width(with(density) { 
                            cellSize * draggedItem.size.width + horizontalSpacing * (draggedItem.size.width - 1) 
                        })
                        .height(with(density) { 
                            cellSize * draggedItem.size.height + verticalSpacing * (draggedItem.size.height - 1) 
                        })
                        .zIndex(50f)
                        .background(
                            if (isPositionOccupied) {
                                // 红色表示不可放置
                                MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                            } else {
                                // 绿色表示可以放置
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                )
            }
        }
    }
}
