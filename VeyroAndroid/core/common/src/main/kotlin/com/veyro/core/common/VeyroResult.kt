package com.veyro.core.common

/**
 * An outcome that carries a typed failure.
 *
 * Kotlin's `Result` is deliberately not used across module boundaries: it boxes a `Throwable`,
 * and most Veyro failures are expected states (peer declined, AP isolated, hash mismatch) rather
 * than exceptions. Modelling them as exceptions encourages catching broadly and losing the
 * reason, which is exactly what REQ-N06 forbids.
 */
public sealed interface VeyroResult<out T, out E> {

    public data class Success<T>(val value: T) : VeyroResult<T, Nothing>

    public data class Failure<E>(val error: E, val cause: Throwable? = null) :
        VeyroResult<Nothing, E>

    public val isSuccess: Boolean get() = this is Success

    public fun valueOrNull(): T? = (this as? Success)?.value

    public fun errorOrNull(): E? = (this as? Failure)?.error
}

public inline fun <T, E, R> VeyroResult<T, E>.map(transform: (T) -> R): VeyroResult<R, E> =
    when (this) {
        is VeyroResult.Success -> VeyroResult.Success(transform(value))
        is VeyroResult.Failure -> this
    }

public inline fun <T, E> VeyroResult<T, E>.onSuccess(action: (T) -> Unit): VeyroResult<T, E> {
    if (this is VeyroResult.Success) action(value)
    return this
}

public inline fun <T, E> VeyroResult<T, E>.onFailure(action: (E) -> Unit): VeyroResult<T, E> {
    if (this is VeyroResult.Failure) action(error)
    return this
}