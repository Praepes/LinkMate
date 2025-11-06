package io.linkmate.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import io.linkmate.data.model.HomeAssistantEntity
import io.linkmate.ui.viewmodels.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntitySelectionScreen(
    navController: NavController,
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val allEntities by homeViewModel.allHaEntities.collectAsState()
    val initialSelectedEntities by homeViewModel.selectedHaEntities.collectAsState()
    
    // 跟踪用户是否修改过选择
    var hasUserModified by remember { mutableStateOf(false) }
    
    // 初始化选中状态，使用 initialSelectedEntities 的内容作为初始�?
    var selectedEntities by remember { 
        mutableStateOf(initialSelectedEntities.toSet()) 
    }
    
    // �?initialSelectedEntities 改变时，如果用户还没修改过，则同步更新本地状�?
    LaunchedEffect(initialSelectedEntities) {
        if (!hasUserModified) {
            selectedEntities = initialSelectedEntities.toSet()
        }
    }
    
    // 处理选择变更
    fun handleSelectionChange(entityId: String, isSelected: Boolean) {
        hasUserModified = true
        if (isSelected) {
            selectedEntities = selectedEntities + entityId
        } else {
            selectedEntities = selectedEntities - entityId
        }
    }

    // Group entities by type for display
    val groupedEntities = allEntities.groupBy { it.type }.toSortedMap()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择 Home Assistant 实体") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                groupedEntities.forEach { (type, entitiesOfType) ->
                    item { 
                        Text(
                            text = type.replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(entitiesOfType) { entity ->
                        EntityItem(
                            entity = entity, 
                            isSelected = selectedEntities.contains(entity.id),
                            onCheckedChange = { isSelected ->
                                handleSelectionChange(entity.id, isSelected)
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    homeViewModel.updateSelectedHomeAssistantEntities(selectedEntities.toList())
                    navController.popBackStack() // Navigate back after saving
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Text("保存选择")
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun EntityItem(entity: HomeAssistantEntity, isSelected: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!isSelected) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = isSelected, onCheckedChange = onCheckedChange)
        Text(text = entity.name, modifier = Modifier.weight(1f))
    }
}
