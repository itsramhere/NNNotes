package com.ramst.notein

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ramst.notein.editor.EditorScreen
import com.ramst.notein.editor.EditorViewModel
import com.ramst.notein.library.LibraryScreen
import com.ramst.notein.library.LibraryViewModel
import com.ramst.notein.ui.theme.NoteinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoteinTheme {
                NoteinApp((application as NoteinApplication).container)
            }
        }
    }
}

@Composable
private fun NoteinApp(container: AppContainer) {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel(
        factory = LibraryViewModelFactory(container),
    )
    NavHost(navController = navController, startDestination = "library") {
        composable("library") {
            LibraryScreen(
                viewModel = libraryViewModel,
                inTrash = false,
                onOpenNotebook = { id -> navController.navigate("notebook/$id") },
                onOpenTrash = { navController.navigate("trash") },
            )
        }
        composable("trash") {
            LibraryScreen(
                viewModel = libraryViewModel,
                inTrash = true,
                onOpenNotebook = { id -> navController.navigate("notebook/$id") },
                onOpenTrash = {},
                onNavigateUp = { navController.popBackStack() },
            )
        }
        composable(
            route = "notebook/{notebookId}",
            arguments = listOf(navArgument("notebookId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val notebookId = requireNotNull(backStackEntry.arguments?.getString("notebookId"))
            val editorViewModel: EditorViewModel = viewModel(
                key = "editor-$notebookId",
                factory = EditorViewModelFactory(container, notebookId),
            )
            EditorScreen(
                viewModel = editorViewModel,
                onNavigateUp = { navController.popBackStack() },
            )
        }
    }
}

private class LibraryViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LibraryViewModel::class.java))
        return LibraryViewModel(container.notebookRepository, container.pdfRepository) as T
    }
}
private class EditorViewModelFactory(
    private val container: AppContainer,
    private val notebookId: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(EditorViewModel::class.java))
        return EditorViewModel(notebookId, container.editorRepository, container.pdfRepository) as T
    }
}
