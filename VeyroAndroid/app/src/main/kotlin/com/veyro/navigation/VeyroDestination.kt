package com.veyro.navigation

import kotlinx.serialization.Serializable

/**
 * Navigation destinations.
 *
 * Type-safe routes (`@Serializable` objects consumed by `composable<T>`) rather than string
 * routes with `navArgument`. The difference is not cosmetic: with strings, a typo in a route or
 * a missing argument is a runtime crash in one specific navigation path, which is exactly the
 * kind of bug that survives testing. Here it is a compile error.
 */
public sealed interface VeyroDestination {

    @Serializable
    public data object Home : VeyroDestination

    @Serializable
    public data object Devices : VeyroDestination

    @Serializable
    public data object History : VeyroDestination

    @Serializable
    public data object Settings : VeyroDestination

    /** Reached from Devices, not from the bottom bar. */
    @Serializable
    public data object Pairing : VeyroDestination

    @Serializable
    public data object Send : VeyroDestination

    @Serializable
    public data object Receive : VeyroDestination

    @Serializable
    public data object Clipboard : VeyroDestination
}
