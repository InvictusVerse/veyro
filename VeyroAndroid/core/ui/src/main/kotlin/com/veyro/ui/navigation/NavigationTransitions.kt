package com.veyro.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

// Every screen change in the app is a horizontal slide combined with a fade of the same length.
private const val SLIDE_FADE_MS = 400

// Stack navigation (pushed screens): the new screen enters from the right edge and the old one
// leaves to the left. Going back plays the same motion mirrored.

internal fun AnimatedContentTransitionScope<*>.pushEnter(): EnterTransition =
    slideIntoContainer(SlideDirection.Left, tween(SLIDE_FADE_MS)) + fadeIn(tween(SLIDE_FADE_MS))

internal fun AnimatedContentTransitionScope<*>.pushExit(): ExitTransition =
    slideOutOfContainer(SlideDirection.Left, tween(SLIDE_FADE_MS)) + fadeOut(tween(SLIDE_FADE_MS))

internal fun AnimatedContentTransitionScope<*>.backEnter(): EnterTransition =
    slideIntoContainer(SlideDirection.Right, tween(SLIDE_FADE_MS)) + fadeIn(tween(SLIDE_FADE_MS))

internal fun AnimatedContentTransitionScope<*>.backExit(): ExitTransition =
    slideOutOfContainer(SlideDirection.Right, tween(SLIDE_FADE_MS)) + fadeOut(tween(SLIDE_FADE_MS))

// Tab switching: tabs are siblings, so the direction follows their order in the bottom bar and
// the caller says which side the movement comes from or goes to.

internal fun tabEnter(fromRight: Boolean): EnterTransition =
    slideInHorizontally(tween(SLIDE_FADE_MS)) { width -> if (fromRight) width else -width } +
        fadeIn(tween(SLIDE_FADE_MS))

internal fun tabExit(toRight: Boolean): ExitTransition =
    slideOutHorizontally(tween(SLIDE_FADE_MS)) { width -> if (toRight) width else -width } +
        fadeOut(tween(SLIDE_FADE_MS))
