package com.veyro.core.common

/**
 * A snapshot of the facts worth knowing when something does not work.
 *
 * Deliberately a plain data class in `:core:common` with no Android imports: the app assembles it
 * from several layers (identity, network, discovery, storage) and the debug screen just renders
 * it. Keeping it dumb means adding a new fact never touches UI code.
 *
 * **Nothing secret goes in here.** The fingerprint and deviceId are public by design; the seed,
 * pairing secrets and session keys must never appear, because this snapshot is built to be copied
 * into a bug report (AGENTS.md).
 */
public data class Diagnostics(
    val sections: List<Section>,
) {
    public data class Section(val title: String, val facts: List<Fact>)

    public data class Fact(
        val label: String,
        val value: String,
        /** Marks a value that indicates a problem, so the UI can highlight it. */
        val isProblem: Boolean = false,
    )

    /** Plain text for the clipboard. */
    public fun toReport(): String = buildString {
        for (section in sections) {
            appendLine("== ${section.title} ==")
            for (fact in section.facts) {
                appendLine("  ${fact.label}: ${fact.value}${if (fact.isProblem) "   <-- PROBLEM" else ""}")
            }
            appendLine()
        }
    }

    public companion object {
        public fun builder(): Builder = Builder()
    }

    public class Builder {
        private val sections = mutableListOf<Section>()

        public fun section(title: String, block: SectionBuilder.() -> Unit): Builder {
            sections += SectionBuilder(title).apply(block).build()
            return this
        }

        public fun build(): Diagnostics = Diagnostics(sections.toList())
    }

    public class SectionBuilder(private val title: String) {
        private val facts = mutableListOf<Fact>()

        public fun fact(label: String, value: String?, isProblem: Boolean = false) {
            facts += Fact(label, value ?: "(unknown)", isProblem)
        }

        public fun problem(label: String, value: String) {
            facts += Fact(label, value, isProblem = true)
        }

        internal fun build(): Section = Section(title, facts.toList())
    }
}