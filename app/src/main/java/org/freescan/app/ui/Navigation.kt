/*
 * Copyright 2025-2026 The FairScan authors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */
package org.freescan.app.ui

sealed class Screen {
    sealed class Main : Screen() {
        object Home : Main()
        object Library : Main()
        object Camera : Main()
        object EditImage : Main()
        data class Document(val initialPage: Int = 0) : Main()
        object Export : Main()
        object ResumeScan : Main()
    }
    sealed class Overlay : Screen() {
        object About : Overlay()
        object Libraries : Overlay()
        object Settings : Overlay()
        object OcrLanguages : Overlay()
    }
}

data class Navigation(
    val toCameraScreen: () -> Unit,
    val toEditImageScreen: () -> Unit,
    val toDocumentScreen: () -> Unit,
    val toExportScreen: () -> Unit,
    val toAboutScreen: () -> Unit,
    val toLibrariesScreen: () -> Unit,
    val toSettingsScreen: (() -> Unit)?,
    val toOcrLanguagesScreen: () -> Unit,
    val back: () -> Unit,
    val shouldDisplayBackButton: () -> Boolean,
    val toHomeScreen: () -> Unit = {},
    val toLibraryScreen: () -> Unit = {},
)

@ConsistentCopyVisibility
data class NavigationState private constructor(val stack: List<Screen>, val root: Screen.Main) {

    companion object {
        fun initial(startAtHome: Boolean = true): NavigationState {
            val root = if (startAtHome) Screen.Main.Home else Screen.Main.Camera
            return NavigationState(listOf(root), root)
        }
    }

    val current: Screen get() = stack.last()

    fun navigateTo(destination: Screen): NavigationState {
        return when {
            destination is Screen.Overlay -> copy(stack = stack + destination)
            destination is Screen.Main.Document && current == Screen.Main.Export &&
                stack.getOrNull(stack.lastIndex - 1) is Screen.Main.Document ->
                copy(stack = stack.dropLast(1))
            destination is Screen.Main.Document &&
                (current == Screen.Main.Home || current == Screen.Main.Library) ->
                copy(stack = stack + destination)
            destination == Screen.Main.EditImage && current is Screen.Main.Document ->
                copy(stack = stack + destination)
            destination == Screen.Main.Export && current is Screen.Main.Document ->
                copy(stack = stack + destination)
            destination == Screen.Main.Camera && root == Screen.Main.Home ->
                copy(stack = if (current == Screen.Main.Home) stack + destination else listOf(root, destination))
            destination == Screen.Main.Library && root == Screen.Main.Home ->
                copy(stack = if (current == Screen.Main.Home) stack + destination else listOf(root, destination))
            else -> copy(stack = listOf(destination))
        }
    }

    fun navigateBack(): NavigationState {
        return when (current) {
            root -> this // Back handled by system
            is Screen.Main.Home -> this // Back handled by system at the home root
            is Screen.Main.ResumeScan -> this // Back handled by system
            is Screen.Main.Camera ->
                if (root == Screen.Main.Home) copy(stack = listOf(root)) else this
            is Screen.Main.Library ->
                if (root == Screen.Main.Home) copy(stack = listOf(root)) else this
            is Screen.Main.Document ->
                if (stack.getOrNull(stack.lastIndex - 1) == Screen.Main.Home ||
                    stack.getOrNull(stack.lastIndex - 1) == Screen.Main.Library)
                    copy(stack = stack.dropLast(1))
                else copy(stack = listOf(Screen.Main.Camera))
            is Screen.Main.EditImage ->
                if (stack.getOrNull(stack.lastIndex - 1) is Screen.Main.Document)
                    copy(stack = stack.dropLast(1))
                else copy(stack = listOf(Screen.Main.Document()))
            is Screen.Main.Export ->
                if (stack.getOrNull(stack.lastIndex - 1) is Screen.Main.Document)
                    copy(stack = stack.dropLast(1))
                else copy(stack = listOf(Screen.Main.Camera))
            is Screen.Overlay -> copy(stack = stack.dropLast(1))
        }
    }
}
