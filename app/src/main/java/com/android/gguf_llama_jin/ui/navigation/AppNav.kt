package com.android.gguf_llama_jin.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.android.gguf_llama_jin.ui.screens.CatalogScreen
import com.android.gguf_llama_jin.ui.screens.ChatScreen
import com.android.gguf_llama_jin.ui.screens.SettingsScreen
import com.android.gguf_llama_jin.ui.viewmodel.AppViewModel
import kotlinx.coroutines.launch

private object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val CATALOG = "catalog"
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AppNav(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val state by viewModel.uiState.collectAsState()
    var chatSearch by rememberSaveable { mutableStateOf("") }
    val filteredThreads = state.threads.filter { thread ->
        if (chatSearch.isBlank()) {
            true
        } else {
            val query = chatSearch.trim()
            thread.title.contains(query, ignoreCase = true) ||
                thread.messages.any { it.text.contains(query, ignoreCase = true) }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentRoute(navController) == Routes.CHAT,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.86f),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Chats",
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(16.dp)
                        )

                        NavigationDrawerItem(
                            label = { Text("New Chat") },
                            selected = false,
                            icon = { Icon(Icons.Filled.Add, contentDescription = "New Chat") },
                            onClick = {
                                viewModel.createNewThread()
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        OutlinedTextField(
                            value = chatSearch,
                            onValueChange = { chatSearch = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Search,
                                    contentDescription = "Search chats"
                                )
                            },
                            placeholder = { Text("Search chat history") },
                            shape = RoundedCornerShape(24.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        LazyColumn(contentPadding = PaddingValues(bottom = 8.dp)) {
                            if (filteredThreads.isEmpty()) {
                                item {
                                    Text(
                                        if (chatSearch.isBlank()) "No chats yet" else "No matching chats",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                            }

                            items(filteredThreads) { thread ->
                                NavigationDrawerItem(
                                    label = { Text(thread.title) },
                                    selected = state.chatMeta.activeThreadId == thread.id,
                                    onClick = {
                                        viewModel.selectThread(thread.id)
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.padding(bottom = 12.dp)) {
                        HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                        NavigationDrawerItem(
                            label = { Text("Settings") },
                            selected = currentRoute(navController) == Routes.SETTINGS,
                            icon = { Icon(Icons.Filled.Settings, contentDescription = "Settings") },
                            onClick = {
                                navController.navigate(Routes.SETTINGS)
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopBar(
                    navController = navController,
                    activeThreadTitle = state.activeThread?.title ?: "New Chat",
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
        ) { padding ->
            NavHost(navController = navController, startDestination = Routes.CHAT) {
                composable(Routes.CHAT) {
                    ChatScreen(
                        viewModel = viewModel,
                        padding = padding,
                        onBrowseModels = { navController.navigate(Routes.CATALOG) }
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(
                        viewModel = viewModel,
                        padding = padding,
                        onBrowseModels = { navController.navigate(Routes.CATALOG) }
                    )
                }
                composable(Routes.CATALOG) {
                    CatalogScreen(viewModel = viewModel, padding = padding)
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TopBar(
    navController: NavHostController,
    activeThreadTitle: String,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val route = currentRoute(navController)
    TopAppBar(
        title = {
            Text(
                when (route) {
                    Routes.CHAT -> activeThreadTitle
                    Routes.SETTINGS -> "Settings"
                    Routes.CATALOG -> "Browse Models"
                    else -> "GGUF Assistant"
                }
            )
        },
        navigationIcon = {
            when (route) {
                Routes.CHAT -> IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                }
                else -> IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        },
        actions = {
            if (route == Routes.CHAT) {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "Open settings")
                }
            }
        }
    )
}

@Composable
private fun currentRoute(navController: NavHostController): String? {
    val backStackEntry by navController.currentBackStackEntryAsState()
    return backStackEntry?.destination?.route
}
