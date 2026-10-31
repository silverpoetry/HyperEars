package dev.hyperears.presentation

/**
 * How the native host's ownership contract resolved in this process. The reason is kept instead of a
 * bare null store, because an absent data plane and a shape mismatch license opposite decisions.
 */
internal enum class OwnershipContractState {
    /** The contract classes and their stores are verified; the store's own readings decide ownership. */
    RESOLVED,

    /** The contract classes do not exist in this build, so there is no native ownership data plane. */
    MISSING,

    /** The classes exist but the verified shape differs; nothing about ownership can be concluded. */
    MISMATCH,
}

/**
 * The contract class was not visible on the loader the module hooks, but the cross-probe could not prove
 * the data plane is absent either. It still carries the original ClassNotFoundException for the log, so the
 * cause walk stops at this marker instead of reading the wrapped exception as confirmed absence.
 */
internal class OwnershipContractUnverifiedException(message: String, cause: ClassNotFoundException) :
    IllegalStateException(message, cause)

/**
 * Admission to use when the native ownership records cannot be read at all. A build without the
 * contract classes cannot have registered the device there, so a session MiLink already handed to
 * the module stays HyperEars'; every other state keeps the conservative unknown and no display.
 */
internal fun fallbackAdmission(contract: OwnershipContractState, milinkAccepted: Boolean): NativeAdmission =
    if (contract == OwnershipContractState.MISSING && milinkAccepted) {
        NativeAdmission.HYPEREARS
    } else NativeAdmission.UNKNOWN

/** A readable record always wins, including its own unknown; the fallback only covers unreadable ones. */
internal fun ownershipAdmission(
    reading: NativeAdmission?,
    contract: OwnershipContractState,
    milinkAccepted: Boolean,
): NativeAdmission = reading ?: fallbackAdmission(contract, milinkAccepted)

/**
 * Only a confirmed absent data plane may fall back. A contract class the injected loader cannot see is
 * treated as absent when the enclosing host service class is missing too, or when the service's own
 * class loader still cannot find the contract class. An unverifiable probe is never absence.
 */
internal fun isContractMissing(outerClassMissing: Boolean, reprobeClassMissing: Boolean): Boolean =
    outerClassMissing || reprobeClassMissing

/** Why resolving the ownership contract failed, with how far the cause chain could be walked. */
internal data class OwnershipContractDiagnosis(
    val state: OwnershipContractState,
    /** Causes inspected, capped at MAX_CAUSE_DEPTH + 1 by the bounded walk. */
    val chainDepth: Int,
    /** The chain was longer than the walk inspects, so the reason was not fully verified. */
    val truncated: Boolean,
)

/**
 * Why a resolve failed: a ClassNotFoundException on the walked causes means the data plane is absent,
 * unless an [OwnershipContractUnverifiedException] is met first. The walk is bounded, so a pathological
 * cause cycle or an over-deep chain reports [OwnershipContractDiagnosis.truncated] instead of hanging.
 */
internal fun ownershipContractDiagnosis(failure: Throwable): OwnershipContractDiagnosis {
    val chain = generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH + 1)
        .toList()
    val reason = chain.take(MAX_CAUSE_DEPTH).firstOrNull {
        it is OwnershipContractUnverifiedException || it is ClassNotFoundException
    }
    return OwnershipContractDiagnosis(
        state = if (reason is ClassNotFoundException) {
            OwnershipContractState.MISSING
        } else OwnershipContractState.MISMATCH,
        chainDepth = chain.size,
        truncated = chain.size > MAX_CAUSE_DEPTH,
    )
}

internal fun ownershipContractStateOf(failure: Throwable): OwnershipContractState =
    ownershipContractDiagnosis(failure).state

internal const val MAX_CAUSE_DEPTH = 8
