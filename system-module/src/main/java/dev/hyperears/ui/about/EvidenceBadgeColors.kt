package dev.hyperears.ui.about

import androidx.compose.ui.graphics.Color

internal data class EvidenceBadgeColors(
    val container: Color,
    val content: Color,
)

/** Stable semantic colors shared by both renderers; evidence meaning must not depend on theme. */
internal fun evidenceBadgeColors(
    evidence: EvidenceLevel,
    dark: Boolean,
): EvidenceBadgeColors = if (dark) {
    when (evidence) {
        EvidenceLevel.VERIFIED -> EvidenceBadgeColors(Color(0xFF173B24), Color(0xFF82D99A))
        EvidenceLevel.PUBLIC_IMPLEMENTATION ->
            EvidenceBadgeColors(Color(0xFF17335B), Color(0xFF8DB8FF))
        EvidenceLevel.REFERENCE_PROTOCOL ->
            EvidenceBadgeColors(Color(0xFF49340F), Color(0xFFF4C66A))
        EvidenceLevel.FAMILY_PROBE ->
            EvidenceBadgeColors(Color(0xFF35234F), Color(0xFFC5A4FF))
        EvidenceLevel.STANDARD_FALLBACK ->
            EvidenceBadgeColors(Color(0xFF34363A), Color(0xFFC4C7CC))
    }
} else {
    when (evidence) {
        EvidenceLevel.VERIFIED -> EvidenceBadgeColors(Color(0xFFDFF6E5), Color(0xFF187A3D))
        EvidenceLevel.PUBLIC_IMPLEMENTATION ->
            EvidenceBadgeColors(Color(0xFFE8F1FF), Color(0xFF1F63C6))
        EvidenceLevel.REFERENCE_PROTOCOL ->
            EvidenceBadgeColors(Color(0xFFFFF1D6), Color(0xFF8A5800))
        EvidenceLevel.FAMILY_PROBE ->
            EvidenceBadgeColors(Color(0xFFF0E7FF), Color(0xFF6C42B8))
        EvidenceLevel.STANDARD_FALLBACK ->
            EvidenceBadgeColors(Color(0xFFECEDEF), Color(0xFF5F6368))
    }
}
