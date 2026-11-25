/* © ARPON — original NOVA AI code. Credits text is fixed content (tested) so it cannot silently drift. */
package com.example.ui.about

object AboutContent {
    const val APP_AND_DEVELOPER = "NOVA AI — Developer: ARPON"
    const val JARVIS_URL = "https://github.com/techgptx/Playlist"
    const val JARVIS_CREDIT =
        "UI design and voice/automation foundations based on JARVIS AI by Tech GPTx ($JARVIS_URL), licensed under TAMCL v1.0."
    /** Maintainer's own project name; shown with the notices, never as a claim about the JARVIS licence. */
    const val PROJECT_MAINTAINER = "NOVA AI project: ARPON X THE TIGER"

    const val NOTICES_ASSET = "legal/THIRD_PARTY_NOTICES.md"
    const val LICENSE_ASSET = "legal/LICENSE-JARVIS-SOURCE.md"

    /** Everything the About + notices screens show as fixed text (used by tests). */
    fun allFixedText(): String = listOf(APP_AND_DEVELOPER, JARVIS_CREDIT, PROJECT_MAINTAINER).joinToString("\n")
}
